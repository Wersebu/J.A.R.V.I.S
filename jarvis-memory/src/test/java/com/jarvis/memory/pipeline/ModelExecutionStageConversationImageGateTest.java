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
import com.jarvis.common.image.ConversationImageContext;
import com.jarvis.common.image.ConversationImageRecord;
import com.jarvis.common.image.ConversationImageStatus;
import com.jarvis.common.image.ImageSelectionReason;
import com.jarvis.common.knowledge.KnowledgeMode;
import com.jarvis.common.model.ActiveModelService;
import com.jarvis.common.model.ModelCapability;
import com.jarvis.common.model.ModelCatalog;
import com.jarvis.common.model.ModelSwitchResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for the deterministic conversation-image gate: when a message references a
 * historical image but {@code ImageAttachmentStage}/{@code ConversationImageResolver} could not
 * merge a real one into {@code context.images()}, {@code ModelExecutionStage} must resolve the
 * outcome itself (ask for a re-upload, or ask which image was meant) and never hand the model an
 * unresolved "can I see this?" question - that is exactly how a several-minutes-long reasoning
 * spiral about the model's own data availability was produced in production.
 */
class ModelExecutionStageConversationImageGateTest {

    @Test
    void ambiguousReferenceShortCircuitsWithoutCallingTheModel() {
        FakeAIProvider provider = new FakeAIProvider();
        ModelExecutionStage stage = stage(provider);
        ConversationImageRecord a = record("a", "village-map.png", ConversationImageStatus.AVAILABLE);
        ConversationImageRecord b = record("b", "village-after.png", ConversationImageStatus.AVAILABLE);
        ConversationImageContext imageContext = new ConversationImageContext(List.of(), List.of(a, b), List.of(),
                List.of(), List.of(), ImageSelectionReason.AMBIGUOUS_REFERENCE);

        PipelineContext result = stage.execute(contextWithImageContext(imageContext));

        assertThat(provider.called).isFalse();
        assertThat(result.response()).contains("village-map.png", "village-after.png");
        assertThat(result.metadata()).containsEntry("mainModelAction", "FINAL_ANSWER");
    }

    @Test
    void expiredReferenceShortCircuitsWithoutCallingTheModel() {
        FakeAIProvider provider = new FakeAIProvider();
        ModelExecutionStage stage = stage(provider);
        ConversationImageRecord expired = record("a", "1000018102.jpg", ConversationImageStatus.EXPIRED);
        ConversationImageContext imageContext = new ConversationImageContext(List.of(), List.of(), List.of(expired),
                List.of(), List.of(), ImageSelectionReason.GENERAL_HISTORICAL_REFERENCE);

        PipelineContext result = stage.execute(contextWithImageContext(imageContext));

        assertThat(provider.called).isFalse();
        assertThat(result.response()).contains("1000018102.jpg").containsIgnoringCase("wyslij");
        assertThat(result.metadata()).containsEntry("mainModelAction", "FINAL_ANSWER");
    }

    @Test
    void missingReferenceShortCircuitsJustLikeExpired() {
        FakeAIProvider provider = new FakeAIProvider();
        ModelExecutionStage stage = stage(provider);
        ConversationImageRecord missing = record("a", "1000018102.jpg", ConversationImageStatus.MISSING);
        ConversationImageContext imageContext = new ConversationImageContext(List.of(), List.of(), List.of(missing),
                List.of(), List.of(), ImageSelectionReason.HISTORICAL_IMAGE_REFERENCE);

        PipelineContext result = stage.execute(contextWithImageContext(imageContext));

        assertThat(provider.called).isFalse();
        assertThat(result.response()).contains("1000018102.jpg");
    }

    // The resolver already merged a real historical image into context.images() (the common,
    // successful case) - the gate must step aside entirely and let the model call proceed normally,
    // never re-asking the user just because a historical reference was involved at all.
    @Test
    void resolvedHistoricalImageProceedsNormallyWithoutGating() {
        FakeAIProvider provider = new FakeAIProvider();
        ModelExecutionStage stage = stage(provider);
        ConversationImageRecord selected = record("a", "1000018102.jpg", ConversationImageStatus.AVAILABLE);
        ConversationImageContext imageContext = new ConversationImageContext(List.of(), List.of(selected), List.of(),
                List.of(selected), List.of(), ImageSelectionReason.HISTORICAL_IMAGE_REFERENCE);
        PipelineContext context = contextWithImageContext(imageContext)
                .withImages(List.of(new ImageAttachment("base64data", "1000018102.jpg", "a")));

        PipelineContext result = stage.execute(context);

        assertThat(provider.called).isTrue();
        assertThat(provider.lastImages).extracting(ImageAttachment::originalFileName).containsExactly("1000018102.jpg");
    }

    @Test
    void noReferenceDetectedProceedsNormally() {
        FakeAIProvider provider = new FakeAIProvider();
        ModelExecutionStage stage = stage(provider);
        ConversationImageContext imageContext = ConversationImageContext.empty();

        PipelineContext result = stage.execute(contextWithImageContext(imageContext));

        assertThat(provider.called).isTrue();
    }

    // A reference was made ("co wyslalem wczesniej?") but this conversation genuinely never had any
    // image at all (available or expired) - nothing to resolve deterministically, so the model must
    // still answer normally (truthfully saying nothing was sent) rather than being blocked.
    @Test
    void referenceWithNoConversationImageHistoryAtAllProceedsNormally() {
        FakeAIProvider provider = new FakeAIProvider();
        ModelExecutionStage stage = stage(provider);
        ConversationImageContext imageContext = new ConversationImageContext(List.of(), List.of(), List.of(),
                List.of(), List.of(), ImageSelectionReason.GENERAL_HISTORICAL_REFERENCE);

        PipelineContext result = stage.execute(contextWithImageContext(imageContext));

        assertThat(provider.called).isTrue();
    }

    private ConversationImageRecord record(String attachmentId, String fileName, ConversationImageStatus status) {
        Instant now = Instant.now();
        return new ConversationImageRecord("id-" + attachmentId, "conversation-1", "m1", 1, 1, "image-1",
                attachmentId, "workspace-1", fileName, "jpg", 1000L, now, now.plusSeconds(3600), status);
    }

    private ModelExecutionStage stage(AIProvider provider) {
        return new ModelExecutionStage(
                List.of(provider),
                context -> "PROMPT",
                new MainModelActionParser(new ObjectMapper()),
                new NoOpCognitiveEventBus(),
                new FakeActiveModelService(Set.of(ModelCapability.TEXT, ModelCapability.TOOLS, ModelCapability.VISION)),
                new AttachmentRetrievalIntentDetector()
        );
    }

    private PipelineContext contextWithImageContext(ConversationImageContext imageContext) {
        return PipelineContext.initial(
                        "conversation-1", "request-1",
                        new ChatRequest("conversation-1", "co wyslalem ci wczesniej w zalaczniku?", null, KnowledgeMode.FAST, List.of()),
                        event -> { }, event -> { })
                .withExecution(null, new Brain(BrainType.FAST, "fake", "gpt-oss:20b", "test"))
                .withMetadata("conversationImageContext", imageContext);
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
            eventSink.publish(TokenEvent.create(conversationId, "{\"type\":\"FINAL_ANSWER\",\"answer\":\"Widze obraz.\"}"));
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
