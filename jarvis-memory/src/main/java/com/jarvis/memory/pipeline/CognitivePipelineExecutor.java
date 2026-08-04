package com.jarvis.memory.pipeline;

import com.jarvis.common.event.CognitiveEventBus;
import com.jarvis.common.event.CognitiveEventType;
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

    /**
     * Creates the pipeline executor.
     *
     * @param stages ordered pipeline stages
     * @param cognitiveEventBus event bus
     */
    public CognitivePipelineExecutor(
            List<PipelineStage> stages,
            CognitiveEventBus cognitiveEventBus
    ) {
        this.stages = List.copyOf(stages);
        this.cognitiveEventBus = cognitiveEventBus;
    }

    /**
     * Runs all configured pipeline stages.
     *
     * @param initialContext initial context
     * @return final pipeline context
     */
    public PipelineContext execute(PipelineContext initialContext) {
        Instant pipelineStartedAt = Instant.now();
        PipelineContext context = initialContext;
        LOGGER.info("[JARVIS] PIPELINE STARTED");
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
            LOGGER.info("[JARVIS] PIPELINE FINISHED ({} ms)", durationMs);
            return context;
        } catch (RuntimeException exception) {
            long durationMs = Duration.between(pipelineStartedAt, Instant.now()).toMillis();
            PipelineContext failedContext = exception instanceof PipelineExecutionException pipelineException
                    ? pipelineException.context()
                    : initialContext;
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
            LOGGER.info("[JARVIS] PIPELINE FINISHED ({} ms)", durationMs);
            return failedContext.withResponse(GRACEFUL_ERROR_RESPONSE, null);
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
            LOGGER.info("[JARVIS] {} OK ({} ms)", stage.name(), durationMs);
            cognitiveEventBus.publish(CognitiveEventType.STAGE_FINISHED, "FINISHED", "Stage finished", stageNode(stage), Map.of(
                    "stageName", stage.name(),
                    "durationMs", durationMs,
                    "success", true
            ));
            return updated.withMetric(metric);
        } catch (RuntimeException exception) {
            long durationMs = Duration.between(startedAt, Instant.now()).toMillis();
            StageMetric metric = new StageMetric(stage.name(), startedAt, Instant.now(), durationMs, false, exception.getMessage());
            LOGGER.info("[JARVIS] {} FAILED ({} ms)", stage.name(), durationMs);
            cognitiveEventBus.publish(CognitiveEventType.STAGE_FINISHED, "FAILED", "Stage failed", stageNode(stage), Map.of(
                    "stageName", stage.name(),
                    "durationMs", durationMs,
                    "success", false,
                    "failure", exception.getMessage() == null ? "" : exception.getMessage()
            ));
            throw new PipelineExecutionException("Pipeline stage failed: " + stage.name(), exception, context.withMetric(metric));
        }
    }

    private String stageNode(PipelineStage stage) {
        return "pipeline:" + stage.name().replace(' ', '-').toLowerCase(java.util.Locale.ROOT);
    }
}
