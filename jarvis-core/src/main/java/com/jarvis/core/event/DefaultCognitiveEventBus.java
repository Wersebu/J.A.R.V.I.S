package com.jarvis.core.event;

import com.jarvis.common.ai.BrainType;
import com.jarvis.common.event.CognitiveEvent;
import com.jarvis.common.event.CognitiveEventBus;
import com.jarvis.common.event.CognitiveEventType;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Thread-local request scoped implementation of the cognitive event bus.
 */
@Service
public class DefaultCognitiveEventBus implements CognitiveEventBus {

    private static final ThreadLocal<RequestScope> REQUEST_SCOPE = new ThreadLocal<>();

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
    }

    /**
     * Finishes a request-scoped event stream.
     */
    @Override
    public void finishRequest() {
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
        scope.sink().accept(new CognitiveEvent(
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
        ));
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
