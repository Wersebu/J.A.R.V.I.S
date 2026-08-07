package com.jarvis.core.diagnostics;

import com.jarvis.common.ai.BrainType;
import com.jarvis.common.diagnostics.ExecutionTracer;
import com.jarvis.common.event.CognitiveEvent;
import com.jarvis.common.event.CognitiveEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Default request-scoped execution tracer.
 */
@Service
public class DefaultExecutionTracer implements ExecutionTracer {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultExecutionTracer.class);
    private static final long BOTTLENECK_THRESHOLD_MS = 500L;
    private static final ThreadLocal<TraceScope> TRACE_SCOPE = new ThreadLocal<>();

    @Override
    public void start(String traceId, String conversationId, Consumer<CognitiveEvent> sink) {
        TRACE_SCOPE.set(new TraceScope(traceId, conversationId, sink == null ? event -> { } : sink));
    }

    @Override
    public void record(
            CognitiveEventType event,
            String status,
            String message,
            BrainType brain,
            String model,
            String nodeId,
            Map<String, Object> metadata
    ) {
        if (event == CognitiveEventType.EXECUTION_TRACE
                || event == CognitiveEventType.EXECUTION_TRACE_SUMMARY
                || event == CognitiveEventType.EXECUTION_BOTTLENECK) {
            return;
        }
        TraceScope scope = TRACE_SCOPE.get();
        if (scope == null) {
            return;
        }
        TraceAction action = action(event, metadata);
        if (action == null) {
            return;
        }
        switch (action.phase()) {
            case START -> startStep(scope, action.stage(), event, status, message, brain, model, nodeId, metadata);
            case FINISH -> finishStep(scope, action.stage(), event, status, message, brain, model, nodeId, metadata);
            case INSTANT -> instantStep(scope, action.stage(), event, status, message, brain, model, nodeId, metadata);
            case SUMMARY -> summary(scope, brain, model);
        }
    }

    @Override
    public void finish() {
        TRACE_SCOPE.remove();
    }

    private void startStep(
            TraceScope scope,
            String stage,
            CognitiveEventType sourceEvent,
            String status,
            String message,
            BrainType brain,
            String model,
            String nodeId,
            Map<String, Object> metadata
    ) {
        scope.starts().put(stage, new RunningStep(stage, Instant.now(), System.nanoTime()));
        emit(scope, sourceEvent, stage, "STARTED", status, message, brain, model, nodeId, 0L, metadata);
    }

    private void finishStep(
            TraceScope scope,
            String stage,
            CognitiveEventType sourceEvent,
            String status,
            String message,
            BrainType brain,
            String model,
            String nodeId,
            Map<String, Object> metadata
    ) {
        RunningStep running = scope.starts().remove(stage);
        long durationMs = explicitDuration(metadata);
        Instant startedAt = Instant.now();
        if (running != null) {
            startedAt = running.startedAt();
            durationMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - running.startedNano());
        }
        CompletedStep completed = new CompletedStep(stage, durationMs);
        scope.completed().add(completed);
        emit(scope, sourceEvent, stage, "FINISHED", status, message, brain, model, nodeId, durationMs, metadata, startedAt);
        if (durationMs > BOTTLENECK_THRESHOLD_MS) {
            bottleneck(scope, stage, durationMs, brain, model, nodeId);
        }
    }

    private void instantStep(
            TraceScope scope,
            String stage,
            CognitiveEventType sourceEvent,
            String status,
            String message,
            BrainType brain,
            String model,
            String nodeId,
            Map<String, Object> metadata
    ) {
        long durationMs = explicitDuration(metadata);
        scope.completed().add(new CompletedStep(stage, durationMs));
        emit(scope, sourceEvent, stage, "FINISHED", status, message, brain, model, nodeId, durationMs, metadata);
        if (durationMs > BOTTLENECK_THRESHOLD_MS) {
            bottleneck(scope, stage, durationMs, brain, model, nodeId);
        }
    }

    private void emit(
            TraceScope scope,
            CognitiveEventType sourceEvent,
            String stage,
            String phase,
            String status,
            String message,
            BrainType brain,
            String model,
            String nodeId,
            long durationMs,
            Map<String, Object> metadata
    ) {
        emit(scope, sourceEvent, stage, phase, status, message, brain, model, nodeId, durationMs, metadata, Instant.now());
    }

    private void emit(
            TraceScope scope,
            CognitiveEventType sourceEvent,
            String stage,
            String phase,
            String status,
            String message,
            BrainType brain,
            String model,
            String nodeId,
            long durationMs,
            Map<String, Object> metadata,
            Instant startedAt
    ) {
        Instant now = Instant.now();
        Map<String, Object> values = new HashMap<>(metadata == null ? Map.of() : metadata);
        values.put("traceId", scope.traceId());
        values.put("stage", stage);
        values.put("phase", phase);
        values.put("sourceEvent", sourceEvent.name());
        values.put("durationMs", durationMs);
        values.put("startedAt", startedAt.toString());
        values.put("endedAt", now.toString());
        values.put("sinceTraceStartMs", java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - scope.startedNano()));
        values.put("severity", severity(durationMs));
        if (status != null && !status.isBlank()) {
            values.put("sourceStatus", status);
        }
        LOGGER.info("[JARVIS][traceId={}][TRACE] stage={} phase={} durationMs={} severity={}",
                scope.traceId(), stage, phase, durationMs, severity(durationMs));
        scope.sink().accept(new CognitiveEvent(
                scope.traceId(),
                scope.conversationId(),
                now,
                CognitiveEventType.EXECUTION_TRACE,
                phase,
                message == null || message.isBlank() ? stage : message,
                brain,
                model,
                nodeId,
                values
        ));
    }

    private void bottleneck(TraceScope scope, String stage, long durationMs, BrainType brain, String model, String nodeId) {
        String severity = durationMs > 5_000L ? "HIGH" : "MEDIUM";
        Map<String, Object> metadata = Map.of(
                "traceId", scope.traceId(),
                "stage", stage,
                "durationMs", durationMs,
                "reason", reason(stage),
                "severity", severity,
                "thresholdMs", BOTTLENECK_THRESHOLD_MS
        );
        scope.sink().accept(new CognitiveEvent(
                scope.traceId(),
                scope.conversationId(),
                Instant.now(),
                CognitiveEventType.EXECUTION_BOTTLENECK,
                severity,
                "Potential Bottleneck",
                brain,
                model,
                nodeId,
                metadata
        ));
    }

    private void summary(TraceScope scope, BrainType brain, String model) {
        Map<String, Long> totals = new HashMap<>();
        for (CompletedStep step : scope.completed()) {
            totals.merge(group(step.stage()), step.durationMs(), Long::sum);
        }
        long total = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - scope.startedNano());
        totals.put("Total", total);
        scope.sink().accept(new CognitiveEvent(
                scope.traceId(),
                scope.conversationId(),
                Instant.now(),
                CognitiveEventType.EXECUTION_TRACE_SUMMARY,
                "FINISHED",
                "Execution trace summary",
                brain,
                model,
                null,
                Map.copyOf(totals)
        ));
    }

    private TraceAction action(CognitiveEventType event, Map<String, Object> metadata) {
        return switch (event) {
            case REQUEST_RECEIVED -> instant("SERVER_RECEIVED");
            case PIPELINE_STARTED -> start("PIPELINE");
            case PIPELINE_FINISHED -> finish("PIPELINE");
            case REQUEST_FINISHED -> summaryAction();
            case STAGE_STARTED -> start(stageName(metadata));
            case STAGE_FINISHED -> finish(stageName(metadata));
            case CONVERSATION_CONTEXT_LOAD_STARTED -> start("CHAT_HISTORY_LOAD");
            case CONVERSATION_CONTEXT_LOADED, CONVERSATION_CONTEXT_EMPTY, CONVERSATION_CONTEXT_ERROR -> finish("CHAT_HISTORY_LOAD");
            case MEMORY_SEARCH_STARTED -> start("MEMORY_LOAD");
            case MEMORY_INJECTED, MEMORY_NOT_FOUND -> finish("MEMORY_LOAD");
            case KNOWLEDGE_SEARCH_STARTED, RESEARCH_SEARCH_STARTED -> start("KNOWLEDGE_SEARCH");
            case KNOWLEDGE_SEARCH_FINISHED, RESEARCH_SEARCH_FINISHED -> finish("KNOWLEDGE_SEARCH");
            case DOCUMENT_FOUND, RESEARCH_DOCUMENT_SELECTED -> instant("KNOWLEDGE_DOCUMENT_SELECT");
            case DOCUMENT_READING_STARTED, DOCUMENT_READ_STARTED -> start("KNOWLEDGE_DOCUMENT_READ");
            case DOCUMENT_READING_FINISHED, DOCUMENT_READ_FINISHED, DOCUMENT_CONTENT_RECEIVED -> finish("KNOWLEDGE_DOCUMENT_READ");
            case TOOL_LOOP_STARTED -> start("TOOL_DISCOVERY");
            case TOOL_SELECTION_STARTED -> start("TOOL_SELECTION");
            case TOOL_CALL_PROPOSED, TOOL_CALL_VALIDATED -> finish("TOOL_SELECTION");
            case TOOL_EXECUTION_STARTED, TOOL_STARTED -> start("TOOL_EXECUTION");
            case TOOL_EXECUTION_FINISHED, TOOL_FINISHED, TOOL_RESULT_RECEIVED -> finish("TOOL_EXECUTION");
            case TOOL_LOOP_FINISHED -> {
                finishImplicit("TOOL_SELECTION", event);
                yield finish("TOOL_DISCOVERY");
            }
            case PROMPT_BUILD_STARTED -> start("PROMPT_BUILD");
            case PROMPT_BUILD_FINISHED -> finish("PROMPT_BUILD");
            case MAIN_MODEL_REQUEST -> start("MAIN_MODEL_REQUEST");
            case MAIN_MODEL_ACTION -> finish("MAIN_MODEL_REQUEST");
            case MODEL_REQUEST_STARTED -> instant("PROMPT_READY");
            case WAITING_FIRST_TOKEN -> start("OLLAMA_FIRST_TOKEN_WAIT");
            case THINKING_STARTED -> {
                finishImplicit("OLLAMA_FIRST_TOKEN_WAIT", event);
                yield start("THINKING_STREAM");
            }
            case THINKING_FINISHED -> finish("THINKING_STREAM");
            case ANSWER_STARTED -> {
                finishImplicit("OLLAMA_FIRST_TOKEN_WAIT", event);
                yield start("ANSWER_STREAM");
            }
            case ANSWER_FINISHED, STREAMING_FINISHED -> finish("ANSWER_STREAM");
            case MEMORY_AGENT_STARTED -> start("MEMORY_UPDATE");
            case MEMORY_AGENT_FINISHED, MEMORY_AGENT_ERROR, MEMORY_SKIPPED -> finish("MEMORY_UPDATE");
            case KNOWLEDGE_WRITE_STARTED -> start("KNOWLEDGE_UPDATE");
            case KNOWLEDGE_WRITE_FINISHED, KNOWLEDGE_WRITE_VERIFIED -> finish("KNOWLEDGE_UPDATE");
            default -> null;
        };
    }

    private TraceAction finishImplicit(String stage, CognitiveEventType sourceEvent) {
        TraceScope scope = TRACE_SCOPE.get();
        if (scope != null && scope.starts().containsKey(stage)) {
            finishStep(scope, stage, sourceEvent, "FINISHED",
                    stage, null, null, null, Map.of());
        }
        return null;
    }

    private TraceAction start(String stage) {
        return new TraceAction(stage, TracePhase.START);
    }

    private TraceAction finish(String stage) {
        return new TraceAction(stage, TracePhase.FINISH);
    }

    private TraceAction instant(String stage) {
        return new TraceAction(stage, TracePhase.INSTANT);
    }

    private TraceAction summaryAction() {
        return new TraceAction("REQUEST_FINISHED", TracePhase.SUMMARY);
    }

    private String stageName(Map<String, Object> metadata) {
        String raw = String.valueOf(metadata == null ? "" : metadata.getOrDefault("stageName", metadata.getOrDefault("stage", "PIPELINE_STAGE")));
        return switch (raw) {
            case "ValidationStage" -> "REQUEST_VALIDATION";
            case "TaskAnalysisStage" -> "TASK_ANALYSIS";
            case "ComplexityStage" -> "COMPLEXITY_ANALYSIS";
            case "MemoryRetrievalStage" -> "MEMORY_LOAD";
            case "KnowledgeRetrievalStage" -> "KNOWLEDGE_SEARCH";
            case "ContextBuilderStage" -> "CONVERSATION_CONTEXT_BUILD";
            case "PromptBuilderStage" -> "PROMPT_BUILD";
            case "ToolCallingStage" -> "TOOL_CALLING";
            case "ModelExecutionStage" -> "MODEL_EXECUTION";
            case "ResponseValidationStage" -> "RESPONSE_VALIDATION";
            default -> raw.replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
        };
    }

    private long explicitDuration(Map<String, Object> metadata) {
        Object value = metadata == null ? null : metadata.get("durationMs");
        if (value instanceof Number number) {
            return number.longValue();
        }
        return 0L;
    }

    private String severity(long durationMs) {
        if (durationMs < 20L) {
            return "GREEN";
        }
        if (durationMs < 100L) {
            return "YELLOW";
        }
        if (durationMs < 500L) {
            return "ORANGE";
        }
        return "RED";
    }

    private String reason(String stage) {
        if (stage.startsWith("OLLAMA") || stage.contains("MODEL") || stage.contains("THINKING")) {
            return "Waiting for local model";
        }
        if (stage.contains("KNOWLEDGE") || stage.contains("DOCUMENT")) {
            return "Knowledge access or indexing work";
        }
        if (stage.contains("TOOL")) {
            return "Tool planning or execution";
        }
        return "Stage exceeded diagnostic threshold";
    }

    private String group(String stage) {
        if (stage.contains("PIPELINE")) {
            return "Pipeline";
        }
        if (stage.contains("MEMORY")) {
            return "Memory";
        }
        if (stage.contains("KNOWLEDGE") || stage.contains("DOCUMENT") || stage.contains("CONTEXT")) {
            return "Knowledge";
        }
        if (stage.contains("TOOL")) {
            return "Tool Calling";
        }
        if (stage.contains("MAIN_MODEL")) {
            return "Main Model";
        }
        if (stage.contains("PROMPT")) {
            return "Prompt Build";
        }
        if (stage.contains("OLLAMA")) {
            return "Ollama Queue";
        }
        if (stage.contains("THINKING")) {
            return "Thinking";
        }
        if (stage.contains("ANSWER")) {
            return "Streaming";
        }
        return "Other";
    }

    private enum TracePhase {
        START,
        FINISH,
        INSTANT,
        SUMMARY
    }

    private record TraceAction(String stage, TracePhase phase) {
    }

    private record RunningStep(String stage, Instant startedAt, long startedNano) {
    }

    private record CompletedStep(String stage, long durationMs) {
    }

    private static final class TraceScope {
        private final String traceId;
        private final String conversationId;
        private final Consumer<CognitiveEvent> sink;
        private final long startedNano;
        private final Map<String, RunningStep> starts;
        private final List<CompletedStep> completed;

        private TraceScope(String traceId, String conversationId, Consumer<CognitiveEvent> sink) {
            this.traceId = traceId;
            this.conversationId = conversationId;
            this.sink = sink;
            this.startedNano = System.nanoTime();
            this.starts = new HashMap<>();
            this.completed = new ArrayList<>();
        }

        private String traceId() {
            return traceId;
        }

        private String conversationId() {
            return conversationId;
        }

        private Consumer<CognitiveEvent> sink() {
            return sink;
        }

        private long startedNano() {
            return startedNano;
        }

        private Map<String, RunningStep> starts() {
            return starts;
        }

        private List<CompletedStep> completed() {
            return completed;
        }
    }
}
