package com.jarvis.memory.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.common.ai.AIJobType;
import com.jarvis.common.ai.AIProvider;
import com.jarvis.common.ai.Brain;
import com.jarvis.common.ai.BrainType;
import com.jarvis.common.ai.ImageAttachment;
import com.jarvis.common.dto.ChatRequest;
import com.jarvis.common.dto.ChatResponse;
import com.jarvis.common.event.ChatEventSink;
import com.jarvis.common.event.CognitiveEvent;
import com.jarvis.common.event.CognitiveEventBus;
import com.jarvis.common.event.CognitiveEventType;
import com.jarvis.common.event.GenerationFinishedEvent;
import com.jarvis.common.event.TokenEvent;
import com.jarvis.common.knowledge.KnowledgeMode;
import com.jarvis.common.model.ActiveModelService;
import com.jarvis.common.model.ModelCapability;
import com.jarvis.common.model.ModelCatalog;
import com.jarvis.common.model.ModelSwitchResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for the internal recovery path in {@link ModelExecutionStage}: a TOOL_REQUEST
 * that redundantly asks a tool to fetch/analyze a current-message image must never be handed off
 * as-is - the stage retries once with an internal corrective note, and gives up cleanly (without
 * looping forever) if the model still gets it wrong afterward.
 */
class ModelExecutionStageAttachmentRoutingTest {

    private static final String BAD_GOAL = "Pobierz i przeanalizuj zalaczony obraz zawierajacy liste adresow sklepow.";
    private static final String GOOD_GOAL = "Geocode the following extracted store addresses: "
            + "Korczaka 7, 08-400 Garwolin; Targowa 1, 08-400 Garwolin.";

    @Test
    void redundantAttachmentRetrievalTriggersOneCorrectiveRetryThenUsesTheCorrectedDecision() {
        ScriptedProvider provider = new ScriptedProvider(List.of(
                finalAnswerFrom(toolRequestJson(BAD_GOAL, "Need to read the image")),
                finalAnswerFrom(toolRequestJson(GOOD_GOAL, "Need coordinates for the schedule"))
        ));
        ModelExecutionStage stage = stage(provider);
        PipelineContext context = contextWithImage();

        PipelineContext result = stage.execute(context);

        assertThat(provider.callCount()).isEqualTo(2);
        assertThat(result.metadata()).containsEntry("mainModelAction", "TOOL_REQUEST");
        assertThat(result.metadata()).containsEntry("toolGoal", GOOD_GOAL);
        assertThat(provider.promptAt(1)).contains("The images attached to the current user message are already available");
    }

    @Test
    void repeatedRedundantAttachmentRetrievalStopsRetryingAfterTheBudgetInsteadOfLoopingForever() {
        ScriptedProvider provider = new ScriptedProvider(List.of(
                finalAnswerFrom(toolRequestJson(BAD_GOAL, "Need to read the image")),
                finalAnswerFrom(toolRequestJson(BAD_GOAL, "Need to read the image")),
                finalAnswerFrom(toolRequestJson(BAD_GOAL, "Need to read the image"))
        ));
        ModelExecutionStage stage = stage(provider);
        PipelineContext context = contextWithImage();

        PipelineContext result = stage.execute(context);

        // One initial attempt + one corrective retry = 2 calls, never the third scripted response.
        assertThat(provider.callCount()).isEqualTo(2);
        assertThat(result.metadata()).containsEntry("mainModelAction", "TOOL_REQUEST");
        assertThat(result.metadata()).containsEntry("toolGoal", BAD_GOAL);
    }

    @Test
    void aCorrectFirstAttemptNeverTriggersARetry() {
        ScriptedProvider provider = new ScriptedProvider(List.of(
                finalAnswerFrom("{\"type\":\"FINAL_ANSWER\",\"answer\":\"Widze adresy: Korczaka 7, Targowa 1.\"}")
        ));
        ModelExecutionStage stage = stage(provider);
        PipelineContext context = contextWithImage();

        PipelineContext result = stage.execute(context);

        assertThat(provider.callCount()).isEqualTo(1);
        assertThat(result.response()).isEqualTo("Widze adresy: Korczaka 7, Targowa 1.");
    }

    @Test
    void attachmentWordingInAToolRequestNeverTriggersARetryWhenThereAreNoCurrentImages() {
        ScriptedProvider provider = new ScriptedProvider(List.of(
                finalAnswerFrom(toolRequestJson(BAD_GOAL, "Need to read the image"))
        ));
        ModelExecutionStage stage = stage(provider);
        PipelineContext context = PipelineContext.initial(
                        "conversation-1", "request-1",
                        new ChatRequest("conversation-1", "opisz obraz z poprzedniej wiadomosci", null, KnowledgeMode.FAST, List.of()),
                        event -> { }, event -> { })
                .withExecution(null, new Brain(BrainType.FAST, "fake", "gpt-oss:20b", "test"));

        PipelineContext result = stage.execute(context);

        assertThat(provider.callCount()).isEqualTo(1);
        assertThat(result.metadata()).containsEntry("toolGoal", BAD_GOAL);
    }

    private static String toolRequestJson(String goal, String reason) {
        return "{\"type\":\"TOOL_REQUEST\",\"goal\":\"" + goal.replace("\"", "\\\"") + "\","
                + "\"reason\":\"" + reason.replace("\"", "\\\"") + "\",\"context\":{}}";
    }

    private static ScriptedTurn finalAnswerFrom(String json) {
        return new ScriptedTurn(json);
    }

    private ModelExecutionStage stage(AIProvider provider) {
        return new ModelExecutionStage(
                List.of(provider),
                context -> "PROMPT",
                new MainModelActionParser(new ObjectMapper()),
                new NoOpCognitiveEventBus(),
                new FakeActiveModelService(Set.of(ModelCapability.TEXT, ModelCapability.VISION)),
                new AttachmentRetrievalIntentDetector()
        );
    }

    private PipelineContext contextWithImage() {
        return PipelineContext.initial(
                        "conversation-1", "request-1",
                        new ChatRequest("conversation-1", "przygotuj grafik na sierpien", null, KnowledgeMode.FAST, List.of()),
                        event -> { }, event -> { })
                .withImages(List.of(new ImageAttachment("base64data", "sklepy.png")))
                .withExecution(null, new Brain(BrainType.VISION, "fake", "gemma3:4b", "test"));
    }

    private record ScriptedTurn(String json) {
    }

    private static final class ScriptedProvider implements AIProvider {

        private final Deque<ScriptedTurn> turns;
        private final List<String> prompts = new ArrayList<>();

        private ScriptedProvider(List<ScriptedTurn> turns) {
            this.turns = new ArrayDeque<>(turns);
        }

        int callCount() {
            return prompts.size();
        }

        String promptAt(int index) {
            return prompts.get(index);
        }

        @Override
        public String provider() {
            return "fake";
        }

        @Override
        public ChatResponse chat(Brain brain, String prompt) {
            return new ChatResponse("");
        }

        @Override
        public void stream(String conversationId, Brain brain, String prompt, ChatEventSink eventSink) {
            throw new UnsupportedOperationException("Not used in this test");
        }

        @Override
        public void stream(String conversationId, Brain brain, String prompt, AIJobType jobType, List<ImageAttachment> images, ChatEventSink eventSink) {
            prompts.add(prompt);
            if (turns.isEmpty()) {
                throw new AssertionError("Unexpected extra model call - no more scripted turns");
            }
            ScriptedTurn turn = turns.poll();
            eventSink.publish(TokenEvent.create(conversationId, turn.json()));
            eventSink.publish(GenerationFinishedEvent.create(conversationId, 1, brain.type(), brain.model(), 10, 5, 5.0d));
        }
    }

    private static final class FakeActiveModelService implements ActiveModelService {

        private final Set<ModelCapability> capabilities;

        private FakeActiveModelService(Set<ModelCapability> capabilities) {
            this.capabilities = capabilities;
        }

        @Override
        public String activeModel() {
            return "test-model";
        }

        @Override
        public Set<ModelCapability> activeModelCapabilities() {
            return capabilities;
        }

        @Override
        public ModelCatalog catalog() {
            return new ModelCatalog(List.of(), activeModel(), true, null);
        }

        @Override
        public ModelSwitchResult switchTo(String requestedModel) {
            return ModelSwitchResult.rejected(activeModel(), "not supported in test");
        }
    }

    private static final class NoOpCognitiveEventBus implements CognitiveEventBus {

        @Override
        public void startRequest(String requestId, String conversationId, Consumer<CognitiveEvent> sink) {
        }

        @Override
        public void finishRequest() {
        }

        @Override
        public void updateBrain(BrainType brain, String model) {
        }

        @Override
        public void publish(CognitiveEventType event, String status, String message, String nodeId, Map<String, Object> metadata) {
        }
    }
}
