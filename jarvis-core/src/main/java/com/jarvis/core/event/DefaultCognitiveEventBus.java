package com.jarvis.core.event;

import com.jarvis.api.websocket.CognitiveEventBroadcaster;
import com.jarvis.common.ai.BrainType;
import com.jarvis.common.diagnostics.ExecutionTracer;
import com.jarvis.common.event.CognitiveEvent;
import com.jarvis.common.event.CognitiveEventBus;
import com.jarvis.common.event.CognitiveEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Thread-local request scoped implementation of the cognitive event bus.
 */
@Service
public class DefaultCognitiveEventBus implements CognitiveEventBus {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultCognitiveEventBus.class);
    private static final ThreadLocal<RequestScope> REQUEST_SCOPE = new ThreadLocal<>();
    private final ExecutionTracer executionTracer;

    /**
     * Creates the event bus.
     *
     * @param executionTracer execution tracer
     */
    public DefaultCognitiveEventBus(ExecutionTracer executionTracer) {
        this.executionTracer = executionTracer;
    }

    /**
     * Starts a request-scoped event stream.
     *
     * @param requestId unique request identifier
     * @param conversationId conversation identifier
     * @param sink event sink
     */
    @Override
    public void startRequest(String requestId, String conversationId, Consumer<CognitiveEvent> sink) {
        REQUEST_SCOPE.set(new RequestScope(requestId, conversationId, sink == null ? event -> { } : sink, null, null));
        executionTracer.start(requestId, conversationId, sink);
    }

    /**
     * Finishes a request-scoped event stream.
     */
    @Override
    public void finishRequest() {
        executionTracer.finish();
        REQUEST_SCOPE.remove();
    }

    /**
     * Updates selected brain metadata for following events.
     *
     * @param brain selected brain
     * @param model selected model
     */
    @Override
    public void updateBrain(BrainType brain, String model) {
        RequestScope scope = REQUEST_SCOPE.get();
        if (scope != null) {
            REQUEST_SCOPE.set(new RequestScope(scope.requestId(), scope.conversationId(), scope.sink(), brain, model));
        }
    }

    /**
     * Publishes a cognitive event in the active request scope.
     *
     * @param event event type
     * @param status short status
     * @param message message
     * @param nodeId logical node identifier, when available
     * @param metadata metadata
     */
    @Override
    public void publish(CognitiveEventType event, String status, String message, String nodeId, Map<String, Object> metadata) {
        RequestScope scope = REQUEST_SCOPE.get();
        if (scope == null) {
            return;
        }
        CognitiveEvent cognitiveEvent = new CognitiveEvent(
                scope.requestId(),
                scope.conversationId(),
                Instant.now(),
                event,
                status,
                message,
                scope.brain(),
                scope.model(),
                nodeId,
                metadata
        );
        scope.sink().accept(cognitiveEvent);
        executionTracer.record(event, status, message, scope.brain(), scope.model(), nodeId, metadata);
    }

    @Override
    public void publishBackground(
            String requestId,
            String conversationId,
            CognitiveEventType event,
            String status,
            String message,
            String nodeId,
            Map<String, Object> metadata
    ) {
        LOGGER.info("[JARVIS][MEMORY][sourceRequestId={}] event={} status={} conversationId={} nodeId={} metadata={}",
                requestId, event, status, conversationId, nodeId, metadata == null ? Map.of() : metadata);
        if (REQUEST_SCOPE.get() != null) {
            // Already delivered through the active request's own sink via publish(...);
            // broadcasting here too would duplicate it and leak it to every other session.
            return;
        }
        CognitiveEvent cognitiveEvent = new CognitiveEvent(
                requestId,
                conversationId,
                Instant.now(),
                event,
                status,
                message,
                null,
                null,
                nodeId,
                metadata
        );
        CognitiveEventBroadcaster.publish(cognitiveEvent);
    }

    private record RequestScope(
            String requestId,
            String conversationId,
            Consumer<CognitiveEvent> sink,
            BrainType brain,
            String model
    ) {
    }
}
