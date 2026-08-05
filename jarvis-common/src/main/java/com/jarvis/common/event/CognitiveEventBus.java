package com.jarvis.common.event;

import com.jarvis.common.ai.BrainType;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Single publication channel for cognitive pipeline activity.
 */
public interface CognitiveEventBus {

    /**
     * Starts a request-scoped event stream.
     *
     * @param requestId unique request identifier
     * @param conversationId conversation identifier
     * @param sink event sink
     */
    void startRequest(String requestId, String conversationId, Consumer<CognitiveEvent> sink);

    /**
     * Finishes a request-scoped event stream.
     */
    void finishRequest();

    /**
     * Updates selected brain metadata for following events.
     *
     * @param brain selected brain
     * @param model selected model
     */
    void updateBrain(BrainType brain, String model);

    /**
     * Publishes a cognitive event in the active request scope.
     *
     * @param event event type
     * @param status short status
     * @param message message
     * @param nodeId logical node identifier, when available
     * @param metadata metadata
     */
    void publish(CognitiveEventType event, String status, String message, String nodeId, Map<String, Object> metadata);

    /**
     * Publishes a background event outside the active request SSE lifecycle.
     *
     * @param requestId source request identifier
     * @param conversationId conversation identifier
     * @param event event type
     * @param status short status
     * @param message message
     * @param nodeId logical node identifier, when available
     * @param metadata metadata
     */
    default void publishBackground(
            String requestId,
            String conversationId,
            CognitiveEventType event,
            String status,
            String message,
            String nodeId,
            Map<String, Object> metadata
    ) {
        publish(event, status, message, nodeId, metadata);
    }

    /**
     * Publishes an error event.
     *
     * @param message error message
     * @param metadata error metadata
     */
    default void error(String message, Map<String, Object> metadata) {
        publish(CognitiveEventType.ERROR, "ERROR", message, null, metadata);
    }
}
