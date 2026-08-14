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

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for the image/vision gate: a message with images must never reach a model that
 * does not report vision support, must reach one that does (with the images actually forwarded),
 * and a plain text message (no images) must behave exactly as before this feature existed.
 */
class ModelExecutionStageVisionGateTest {

    @Test
    void modelWithoutVisionNeverReceivesTheCallWhenImagesArePresent() {
        FakeAIProvider provider = new FakeAIProvider();
        FakeActiveModelService activeModelService = new FakeActiveModelService(Set.of(ModelCapability.TEXT, ModelCapability.TOOLS));
        ModelExecutionStage stage = stage(provider, activeModelService);

        PipelineContext context = contextWithImage("gpt-oss:20b");

        PipelineContext result = stage.execute(context);

        assertThat(provider.called).isFalse();
        assertThat(result.response()).contains("nie obsluguje analizy obrazow");
        assertThat(result.metadata()).containsEntry("mainModelAction", "FINAL_ANSWER");
    }

    @Test
    void modelWithVisionReceivesTheImagesUnchanged() {
        FakeAIProvider provider = new FakeAIProvider();
        FakeActiveModelService activeModelService = new FakeActiveModelService(Set.of(ModelCapability.TEXT, ModelCapability.VISION));
        ModelExecutionStage stage = stage(provider, activeModelService);

        PipelineContext context = contextWithImage("gemma3:4b");

        PipelineContext result = stage.execute(context);

        assertThat(provider.called).isTrue();
        assertThat(provider.lastImages).hasSize(1);
        assertThat(provider.lastImages.getFirst().originalFileName()).isEqualTo("photo.png");
        assertThat(result.response()).isEqualTo("I see a cat in the image.");
    }

    @Test
    void plainTextMessageWithoutImagesIsUnaffectedByTheVisionGate() {
        FakeAIProvider provider = new FakeAIProvider();
        FakeActiveModelService activeModelService = new FakeActiveModelService(Set.of(ModelCapability.TEXT, ModelCapability.TOOLS));
        ModelExecutionStage stage = stage(provider, activeModelService);

        PipelineContext context = PipelineContext.initial(
                        "conversation-1", "request-1",
                        new ChatRequest("conversation-1", "hello", null, KnowledgeMode.FAST, List.of()),
                        event -> { }, event -> { })
                .withExecution(null, new Brain(BrainType.FAST, "fake", "gpt-oss:20b", "test"));

        PipelineContext result = stage.execute(context);

        assertThat(provider.called).isTrue();
        assertThat(provider.lastImages).isEmpty();
        assertThat(result.response()).isEqualTo("I see a cat in the image.");
    }

    private ModelExecutionStage stage(AIProvider provider, ActiveModelService activeModelService) {
        return new ModelExecutionStage(
                List.of(provider),
                context -> "PROMPT",
                new MainModelActionParser(new ObjectMapper()),
                new NoOpCognitiveEventBus(),
                activeModelService
        );
    }

    private PipelineContext contextWithImage(String model) {
        return PipelineContext.initial(
                        "conversation-1", "request-1",
                        new ChatRequest("conversation-1", "Co jest na tym zdjeciu?", null, KnowledgeMode.FAST, List.of()),
                        event -> { }, event -> { })
                .withImages(List.of(new ImageAttachment("base64data", "photo.png")))
                .withExecution(null, new Brain(BrainType.VISION, "fake", model, "test"));
    }

    private static final class FakeAIProvider implements AIProvider {

        private boolean called;
        private List<ImageAttachment> lastImages = List.of();

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
            throw new UnsupportedOperationException("ModelExecutionStage must use the images-aware overload");
        }

        @Override
        public void stream(String conversationId, Brain brain, String prompt, AIJobType jobType, List<ImageAttachment> images, ChatEventSink eventSink) {
            called = true;
            lastImages = images;
            eventSink.publish(TokenEvent.create(conversationId, "{\"type\":\"FINAL_ANSWER\",\"answer\":\"I see a cat in the image.\"}"));
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
