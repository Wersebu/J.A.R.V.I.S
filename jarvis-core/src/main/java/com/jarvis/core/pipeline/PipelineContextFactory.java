package com.jarvis.core.pipeline;

import com.jarvis.common.dto.ChatRequest;
import com.jarvis.common.diagnostics.InferenceDiagnostics;
import com.jarvis.common.diagnostics.InferenceDiagnosticsContext;
import com.jarvis.common.diagnostics.InferenceDiagnosticsService;
import com.jarvis.common.event.ChatEventSink;
import com.jarvis.common.event.CognitiveEvent;
import com.jarvis.common.event.CognitiveEventBus;
import com.jarvis.common.event.CognitiveEventType;
import com.jarvis.core.diagnostics.ConversationTraceRecorder;
import com.jarvis.memory.pipeline.PipelineContext;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Creates request-scoped cognitive pipeline contexts.
 */
@Service
public class PipelineContextFactory {

    @org.springframework.beans.factory.annotation.Autowired(required=false) private com.jarvis.common.companion.ActivityTracker activities;
    private final ThreadLocal<com.jarvis.common.companion.ActivityTracker.Scope> active=new ThreadLocal<>();
    private final CognitiveEventBus cognitiveEventBus;
    private final InferenceDiagnosticsService diagnosticsService;
    private final ConversationTraceRecorder traceRecorder;

    /**
     * Creates the context factory.
     *
     * @param cognitiveEventBus cognitive event bus
     */
    public PipelineContextFactory(
            CognitiveEventBus cognitiveEventBus,
            InferenceDiagnosticsService diagnosticsService,
            ConversationTraceRecorder traceRecorder
    ) {
        this.cognitiveEventBus = cognitiveEventBus;
        this.diagnosticsService = diagnosticsService;
        this.traceRecorder = traceRecorder;
    }

    /**
     * Creates a pipeline context for a normal chat request.
     *
     * @param request chat request
     * @return pipeline context
     */
    public PipelineContext create(ChatRequest request) {
        return create(request, event -> { });
    }

    /**
     * Creates a pipeline context for a streaming chat request.
     *
     * @param request chat request
     * @param eventSink cognitive event sink
     * @return pipeline context
     */
    public PipelineContext create(ChatRequest request, Consumer<CognitiveEvent> eventSink) {
        String conversationId = normalizeConversationId(request.conversationId());
        var interactive = com.jarvis.common.run.InteractiveRequestContext.current();
        UUID requestUuid = interactive == null ? UUID.randomUUID() : UUID.fromString(interactive.requestId());
        String requestId = requestUuid.toString();
        if(activities!=null&&com.jarvis.common.companion.TaskExecutionContext.current()==null)active.set(activities.start("INTERACTIVE",requestId,"conversation",null));
        Instant serverReceivedAt = Instant.now();
        InferenceDiagnostics diagnostics = diagnosticsService.create(requestUuid, conversationId);
        diagnostics.setClientRequestTimestamp(request.clientRequestTimestamp());
        diagnostics.setServerReceivedTimestamp(serverReceivedAt);
        if (request.clientRequestTimestamp() != null) {
            diagnostics.setClientToServerMs(Duration.between(request.clientRequestTimestamp(), serverReceivedAt).toMillis());
        }
        InferenceDiagnosticsContext.bind(diagnostics);
        eventSink = traceRecorder.begin(requestId, conversationId, request.message(), eventSink);
        cognitiveEventBus.startRequest(requestId, conversationId, eventSink);
        cognitiveEventBus.publish(CognitiveEventType.REQUEST_RECEIVED, "RECEIVED", "Incoming request", null, Map.of(
                "requestId", requestId,
                "conversationId", conversationId,
                "serverReceivedAt", serverReceivedAt.toString(),
                "messageLength", request.message() == null ? 0 : request.message().length(),
                "attachments", request.attachments().size()
        ));
        ChatEventSink modelEventSink = event -> { };
        return PipelineContext.initial(
                conversationId,
                requestId,
                new ChatRequest(
                        conversationId,
                        request.message(),
                        request.clientRequestTimestamp(),
                        request.knowledgeMode(),
                        request.attachments(),
                        request.activeCodingWorkspaceId(),
                        request.activeCodingWorkspaceName(),
                        request.activeCodingWorkspaceHost(),
                        request.workingDirectory(),
                        request.voiceMode()
                ),
                modelEventSink,
                eventSink
        );
    }

    /**
     * Finishes the current request context.
     */
    public void finish() {
        var activity=active.get();active.remove();if(activity!=null)activity.close();
        try { traceRecorder.finish(); }
        finally { try { cognitiveEventBus.finishRequest(); } finally { InferenceDiagnosticsContext.clear(); } }
    }

    private String normalizeConversationId(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return conversationId;
    }
}
