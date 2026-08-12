package com.jarvis.memory.pipeline;

import com.jarvis.brain.decision.ExecutionPlan;
import com.jarvis.brain.decision.TaskType;
import com.jarvis.common.ai.Brain;
import com.jarvis.common.ai.BrainType;
import com.jarvis.common.ai.ReasoningLevel;
import com.jarvis.common.dto.AttachmentReference;
import com.jarvis.common.dto.ChatRequest;
import com.jarvis.common.event.CognitiveEvent;
import com.jarvis.common.event.CognitiveEventBus;
import com.jarvis.common.event.CognitiveEventType;
import com.jarvis.common.knowledge.KnowledgeMode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeModeStageTest {

    @Test
    void autoNeverEscalatesToResearchRegardlessOfTaskTypeOrComplexity() {
        KnowledgeModeStage stage = new KnowledgeModeStage(new NoopCognitiveEventBus());
        ChatRequest request = new ChatRequest(
                "conversation",
                "przeanalizuj kod tego projektu i zrob code review",
                Instant.now(),
                KnowledgeMode.AUTO,
                List.of()
        );
        PipelineContext context = PipelineContext.initial("conversation", "request", request, event -> { }, event -> { })
                .withExecution(
                        new ExecutionPlan(TaskType.CODE_REVIEW, 0.95, 9, true, 5, 2000,
                                BrainType.REASONING, "stub-model", ReasoningLevel.HIGH, "Code review request"),
                        new Brain(BrainType.REASONING, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.HIGH)
                );

        PipelineContext result = stage.execute(context);

        assertThat(result.effectiveKnowledgeMode()).isEqualTo(KnowledgeMode.FAST);
    }

    @Test
    void temporaryAttachmentsUseFastPromptContextEvenWhenResearchWasRequested() {
        KnowledgeModeStage stage = new KnowledgeModeStage(new NoopCognitiveEventBus());
        ChatRequest request = new ChatRequest(
                "conversation",
                "pytam o pliki ktore sa w zalaczniku",
                Instant.now(),
                KnowledgeMode.RESEARCH,
                List.of(new AttachmentReference("workspace", "attachment"))
        );
        PipelineContext context = PipelineContext.initial("conversation", "request", request, event -> { }, event -> { });

        PipelineContext result = stage.execute(context);

        assertThat(result.effectiveKnowledgeMode()).isEqualTo(KnowledgeMode.FAST);
    }

    @Test
    void explicitResearchIsPreservedWhenThereAreNoAttachments() {
        KnowledgeModeStage stage = new KnowledgeModeStage(new NoopCognitiveEventBus());
        ChatRequest request = new ChatRequest(
                "conversation",
                "przeanalizuj baze wiedzy",
                Instant.now(),
                KnowledgeMode.RESEARCH,
                List.of()
        );
        PipelineContext context = PipelineContext.initial("conversation", "request", request, event -> { }, event -> { });

        PipelineContext result = stage.execute(context);

        assertThat(result.effectiveKnowledgeMode()).isEqualTo(KnowledgeMode.RESEARCH);
    }

    private static final class NoopCognitiveEventBus implements CognitiveEventBus {

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
