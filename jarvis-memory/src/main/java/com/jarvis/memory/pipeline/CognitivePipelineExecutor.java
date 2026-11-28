package com.jarvis.memory.pipeline;

import com.jarvis.common.diagnostics.InferenceDiagnostics;
import com.jarvis.common.diagnostics.InferenceDiagnosticsContext;
import com.jarvis.common.event.CognitiveEventBus;
import com.jarvis.common.event.CognitiveEventType;
import com.jarvis.memory.debug.PipelineDebugService;
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

    @org.springframework.beans.factory.annotation.Autowired(required=false) private com.jarvis.memory.ConversationMemoryService failedAnswerMemory;
    private final List<PipelineStage> stages;
    private final CognitiveEventBus cognitiveEventBus;
    private final PipelineDebugService pipelineDebugService;

    /**
     * Creates the pipeline executor.
     *
     * @param stages ordered pipeline stages
     * @param cognitiveEventBus event bus
     * @param pipelineDebugService debug snapshot service
     */
    public CognitivePipelineExecutor(
            List<PipelineStage> stages,
            CognitiveEventBus cognitiveEventBus,
            PipelineDebugService pipelineDebugService
    ) {
        this.stages = List.copyOf(stages);
        this.cognitiveEventBus = cognitiveEventBus;
        this.pipelineDebugService = pipelineDebugService;
    }

    /**
     * Runs all configured pipeline stages.
     *
     * @param initialContext initial context
     * @return final pipeline context
     */
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.jarvis.common.ai.InferenceControl inferenceControl;

    public PipelineContext execute(PipelineContext initialContext) {
        var pinned=com.jarvis.common.ai.InferenceSelectionContext.current();
        var selection=pinned!=null?pinned:inferenceControl==null?null:inferenceControl.selection();
        return com.jarvis.common.ai.InferenceSelectionContext.call(selection,()->executePinned(initialContext));
    }

    private PipelineContext executePinned(PipelineContext initialContext) {
        Instant pipelineStartedAt = Instant.now();
        long pipelineStartedNano = System.nanoTime();
        PipelineContext context = initialContext;
        InferenceDiagnostics diagnostics = InferenceDiagnosticsContext.current();
        if (diagnostics != null) {
            diagnostics.setPipelineStartupMs(elapsedSinceRequest(diagnostics));
        }
        LOGGER.info("[JARVIS][requestId={}][PIPELINE] STARTED elapsedMs={}", context.requestId(), elapsedSinceRequest(diagnostics));
        cognitiveEventBus.publish(CognitiveEventType.PIPELINE_STARTED, "STARTED", "Cognitive pipeline started", null, Map.of(
                "stages", stages.size()
        ));

        try {
            for (PipelineStage stage : stages) {
                boolean handled=Boolean.TRUE.equals(context.metadata().get("semanticOrchestration"))&&java.util.Set.of("DIRECT","DELEGATE").contains(String.valueOf(context.metadata().get("orchestrationAction")));
                if(handled&&java.util.Set.of("KnowledgeModeStage","KnowledgeRetrievalStage","ContextBuilderStage","PromptBuilderStage","ModelExecutionStage","ToolCallingStage").contains(stage.name()))continue;
                if(com.jarvis.common.companion.AgentScope.current()!=null&&java.util.Set.of("MemoryRetrievalStage","KnowledgeRetrievalStage","ContextBuilderStage").contains(stage.name()))continue;
                context = executeStage(stage, context);
            }
            long durationMs = Duration.between(pipelineStartedAt, Instant.now()).toMillis();
            if (diagnostics != null) {
                diagnostics.setTotalRequestMs(nanosToMillis(System.nanoTime() - diagnostics.getCreatedNanoTime()));
            }
            cognitiveEventBus.publish(CognitiveEventType.PIPELINE_FINISHED, "FINISHED", "Cognitive pipeline finished", null, Map.of(
                    "durationMs", durationMs,
                    "stages", context.metrics().size(),
                    "success", true
            ));
            LOGGER.info("[JARVIS][requestId={}][PIPELINE] FINISHED totalMs={}", context.requestId(), durationMs);
            logSummary(context, diagnostics, pipelineStartedNano);
            return context;
        } catch (RuntimeException exception) {
            long durationMs = Duration.between(pipelineStartedAt, Instant.now()).toMillis();
            PipelineContext failedContext = exception instanceof PipelineExecutionException pipelineException
                    ? pipelineException.context()
                    : initialContext;
            String visible=cognitiveEventBus.streamedAnswer();
            if(failedAnswerMemory!=null&&!visible.isEmpty())try{
                var partial=com.jarvis.common.event.AssistantResponse.message(com.jarvis.common.auth.CurrentUserContext.requiredUserId(),initialContext.conversationId(),initialContext.requestId(),visible,Instant.now());
                failedAnswerMemory.addMessage(initialContext.conversationId(),new com.jarvis.common.memory.ConversationMessage(partial.id(),partial.conversationId(),partial.requestId(),partial.role(),partial.content(),partial.createdAt(),0,partial.messageType(),com.jarvis.common.memory.ConversationMessageStatus.FAILED));
            }catch(RuntimeException persistenceFailure){exception.addSuppressed(persistenceFailure);}
            LOGGER.error("[JARVIS][requestId={}][PIPELINE] FAILED elapsedMs={}", failedContext.requestId(), durationMs, exception);
            cognitiveEventBus.error("Cognitive pipeline failed", Map.of(
                    "exception", exception.getClass().getSimpleName(),
                    "message", exception.getMessage() == null ? "" : exception.getMessage(),
                    "durationMs", durationMs
            ));
            cognitiveEventBus.publish(CognitiveEventType.PIPELINE_FINISHED, "FAILED", "Cognitive pipeline failed", null, Map.of(
                    "durationMs", durationMs,
                    "success", false
            ));
            if (diagnostics != null) {
                diagnostics.setTotalRequestMs(nanosToMillis(System.nanoTime() - diagnostics.getCreatedNanoTime()));
            }
            LOGGER.info("[JARVIS][requestId={}][PIPELINE] FINISHED totalMs={}", failedContext.requestId(), durationMs);
            logSummary(failedContext, diagnostics, pipelineStartedNano);
            if(com.jarvis.common.companion.TaskExecutionContext.current()!=null){
                for(Throwable cause=exception;cause!=null;cause=cause.getCause())if(cause instanceof com.jarvis.common.companion.IncompleteTaskException incomplete)throw incomplete;
                throw new com.jarvis.common.companion.IncompleteTaskException("PIPELINE_FAILED: "+exception.getClass().getSimpleName(),failedContext.response());
            }
            throw new IllegalStateException("Request failed before durable answer delivery",exception);
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
            updateDiagnostics(stage.name(), durationMs, updated);
            LOGGER.info("[JARVIS][requestId={}][PIPELINE] STAGE_FINISHED stage={} status=OK elapsedMs={}",
                    context.requestId(), stage.name(), durationMs);
            cognitiveEventBus.publish(CognitiveEventType.STAGE_FINISHED, "FINISHED", "Stage finished", stageNode(stage), Map.of(
                    "stageName", stage.name(),
                    "durationMs", durationMs,
                    "success", true
            ));
            PipelineContext updatedWithMetric = updated.withMetric(metric);
            pipelineDebugService.update(updatedWithMetric);
            return updatedWithMetric;
        } catch (RuntimeException exception) {
            long durationMs = Duration.between(startedAt, Instant.now()).toMillis();
            StageMetric metric = new StageMetric(stage.name(), startedAt, Instant.now(), durationMs, false, exception.getMessage());
            updateDiagnostics(stage.name(), durationMs, context);
            LOGGER.info("[JARVIS][requestId={}][PIPELINE] STAGE_FINISHED stage={} status=FAILED elapsedMs={}",
                    context.requestId(), stage.name(), durationMs);
            cognitiveEventBus.publish(CognitiveEventType.STAGE_FINISHED, "FAILED", "Stage failed", stageNode(stage), Map.of(
                    "stageName", stage.name(),
                    "durationMs", durationMs,
                    "success", false,
                    "failure", exception.getMessage() == null ? "" : exception.getMessage()
            ));
            PipelineContext failedContext = context.withMetric(metric);
            pipelineDebugService.update(failedContext);
            throw new PipelineExecutionException("Pipeline stage failed: " + stage.name(), exception, failedContext);
        }
    }

    private String stageNode(PipelineStage stage) {
        return "pipeline:" + stage.name().replace(' ', '-').toLowerCase(java.util.Locale.ROOT);
    }

    private void updateDiagnostics(String stageName, long durationMs, PipelineContext context) {
        InferenceDiagnostics diagnostics = InferenceDiagnosticsContext.current();
        if (diagnostics == null) {
            return;
        }
        switch (stageName) {
            case "ValidationStage" -> diagnostics.setValidationMs(durationMs);
            case "TaskAnalysisStage" -> diagnostics.setTaskAnalysisMs(durationMs);
            case "ComplexityStage" -> diagnostics.setComplexityAnalysisMs(durationMs);
            case "MemoryRetrievalStage" -> {
                diagnostics.setMemoryRetrievalMs(durationMs);
                diagnostics.setMemoryItemsInjected(context.memoryContext().memoryCount());
            }
            case "KnowledgeRetrievalStage" -> diagnostics.setKnowledgeRetrievalMs(durationMs);
            case "ContextBuilderStage" -> {
                diagnostics.setContextBuildMs(durationMs);
                diagnostics.setKnowledgeDocumentsInjected(context.knowledgeContext().sourceCount());
            }
            case "PromptBuilderStage" -> diagnostics.setPromptBuildMs(durationMs);
            default -> {
            }
        }
        if (context.prompt() != null && !context.prompt().isBlank()) {
            diagnostics.setPromptCharacters(context.prompt().length());
            diagnostics.setEstimatedPromptTokens(context.prompt().length() / 4);
        }
        if (context.brain() != null) {
            diagnostics.setModel(context.brain().model());
            diagnostics.setReasoningLevel(context.brain().reasoningLevel().name());
        }
    }

    private long elapsedSinceRequest(InferenceDiagnostics diagnostics) {
        if (diagnostics == null) {
            return 0L;
        }
        return nanosToMillis(System.nanoTime() - diagnostics.getCreatedNanoTime());
    }

    private long nanosToMillis(long nanos) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(nanos);
    }

    private void logSummary(PipelineContext context, InferenceDiagnostics diagnostics, long pipelineStartedNano) {
        if (diagnostics == null) {
            return;
        }
        long totalMs = diagnostics.getTotalRequestMs() == null
                ? nanosToMillis(System.nanoTime() - diagnostics.getCreatedNanoTime())
                : diagnostics.getTotalRequestMs();
        LOGGER.info("""
                [JARVIS][requestId={}] REQUEST TIMING SUMMARY
                Pipeline runtime............ {} ms
                Executor queue.............. {} ms
                Memory retrieval............ {} ms
                Knowledge retrieval......... {} ms
                Context build............... {} ms
                Prompt build................ {} ms
                Ollama queue wait........... {} ms
                Ollama response headers..... {} ms
                First thinking chunk........ {} ms
                Thinking duration........... {} ms
                First answer token.......... {} ms
                Answer streaming............ {} ms
                Total....................... {} ms
                """,
                context.requestId(),
                nanosToMillis(System.nanoTime() - pipelineStartedNano),
                value(diagnostics.getExecutorQueueWaitMs()),
                value(diagnostics.getMemoryRetrievalMs()),
                value(diagnostics.getKnowledgeRetrievalMs()),
                value(diagnostics.getContextBuildMs()),
                value(diagnostics.getPromptBuildMs()),
                value(diagnostics.getOllamaPermitQueueWaitMs()),
                value(diagnostics.getResponseHeadersReceivedMs()),
                value(diagnostics.getFirstThinkingTokenMs()),
                value(diagnostics.getThinkingDurationMs()),
                value(diagnostics.getFirstAnswerTokenMs()),
                value(diagnostics.getAnswerStreamingDurationMs()),
                totalMs);
    }

    private String value(Long value) {
        return value == null ? "unavailable" : value.toString();
    }
}
