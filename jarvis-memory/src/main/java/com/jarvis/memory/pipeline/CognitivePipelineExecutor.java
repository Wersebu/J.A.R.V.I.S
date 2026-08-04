package com.jarvis.memory.pipeline;

import com.jarvis.common.dto.ChatResponse;
import com.jarvis.common.event.CognitiveEventBus;
import com.jarvis.common.event.CognitiveEventType;
import com.jarvis.common.memory.ConversationMessage;
import com.jarvis.common.memory.MessageRole;
import com.jarvis.memory.ConversationMemoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Executes the cognitive pipeline sequentially and records stage metrics.
 */
@Service
public class CognitivePipelineExecutor {

    private static final Logger LOGGER = LoggerFactory.getLogger(CognitivePipelineExecutor.class);
    private static final String GRACEFUL_ERROR_RESPONSE = "I could not process this request safely. Please try again.";

    private final List<PipelineStage> stages;
    private final CognitiveEventBus cognitiveEventBus;
    private final ConversationMemoryService memoryService;

    /**
     * Creates the pipeline executor.
     *
     * @param stages ordered pipeline stages
     * @param cognitiveEventBus event bus
     * @param memoryService memory service
     */
    public CognitivePipelineExecutor(
            List<PipelineStage> stages,
            CognitiveEventBus cognitiveEventBus,
            ConversationMemoryService memoryService
    ) {
        this.stages = List.copyOf(stages);
        this.cognitiveEventBus = cognitiveEventBus;
        this.memoryService = memoryService;
    }

    /**
     * Runs all configured pipeline stages.
     *
     * @param initialContext initial context
     * @return final chat response
     */
    public ChatResponse execute(PipelineContext initialContext) {
        Instant pipelineStartedAt = Instant.now();
        PipelineContext context = initialContext;
        cognitiveEventBus.publish(CognitiveEventType.PIPELINE_STARTED, "STARTED", "Cognitive pipeline started", null, Map.of(
                "stages", stages.size()
        ));

        try {
            for (PipelineStage stage : stages) {
                context = executeStage(stage, context);
            }
            long durationMs = Duration.between(pipelineStartedAt, Instant.now()).toMillis();
            cognitiveEventBus.publish(CognitiveEventType.PIPELINE_FINISHED, "FINISHED", "Cognitive pipeline finished", null, Map.of(
                    "durationMs", durationMs,
                    "stages", context.metrics().size(),
                    "success", true
            ));
            persistAssistantResponse(context);
            return new ChatResponse(context.response());
        } catch (RuntimeException exception) {
            long durationMs = Duration.between(pipelineStartedAt, Instant.now()).toMillis();
            LOGGER.error("[JARVIS] Cognitive pipeline failed", exception);
            cognitiveEventBus.error("Cognitive pipeline failed", Map.of(
                    "exception", exception.getClass().getSimpleName(),
                    "message", exception.getMessage() == null ? "" : exception.getMessage(),
                    "durationMs", durationMs
            ));
            cognitiveEventBus.publish(CognitiveEventType.PIPELINE_FINISHED, "FAILED", "Cognitive pipeline failed", null, Map.of(
                    "durationMs", durationMs,
                    "success", false
            ));
            memoryService.addMessage(
                    initialContext.conversationId(),
                    new ConversationMessage(MessageRole.ASSISTANT, GRACEFUL_ERROR_RESPONSE, Instant.now())
            );
            return new ChatResponse(GRACEFUL_ERROR_RESPONSE);
        }
    }

    private PipelineContext executeStage(PipelineStage stage, PipelineContext context) {
        Instant startedAt = Instant.now();
        cognitiveEventBus.publish(CognitiveEventType.STAGE_STARTED, "STARTED", "Stage started", stageNode(stage), Map.of(
                "stageName", stage.name()
        ));
        try {
            PipelineContext updated = stage.execute(context);
            long durationMs = Duration.between(startedAt, Instant.now()).toMillis();
            StageMetric metric = new StageMetric(stage.name(), startedAt, Instant.now(), durationMs, true, "");
            cognitiveEventBus.publish(CognitiveEventType.STAGE_FINISHED, "FINISHED", "Stage finished", stageNode(stage), Map.of(
                    "stageName", stage.name(),
                    "durationMs", durationMs,
                    "success", true
            ));
            return updated.withMetric(metric);
        } catch (RuntimeException exception) {
            long durationMs = Duration.between(startedAt, Instant.now()).toMillis();
            StageMetric metric = new StageMetric(stage.name(), startedAt, Instant.now(), durationMs, false, exception.getMessage());
            cognitiveEventBus.publish(CognitiveEventType.STAGE_FINISHED, "FAILED", "Stage failed", stageNode(stage), Map.of(
                    "stageName", stage.name(),
                    "durationMs", durationMs,
                    "success", false,
                    "failure", exception.getMessage() == null ? "" : exception.getMessage()
            ));
            throw new PipelineExecutionException("Pipeline stage failed: " + stage.name(), exception, context.withMetric(metric));
        }
    }

    private void persistAssistantResponse(PipelineContext context) {
        memoryService.addMessage(
                context.conversationId(),
                new ConversationMessage(MessageRole.ASSISTANT, context.response(), Instant.now())
        );
    }

    private String stageNode(PipelineStage stage) {
        return "pipeline:" + stage.name().replace(' ', '-').toLowerCase(java.util.Locale.ROOT);
    }
}
