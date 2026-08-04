package com.jarvis.common.event;

import com.jarvis.common.ai.BrainType;

import java.time.Instant;
import java.util.Map;

/**
 * Unified real-time event describing one step in the J.A.R.V.I.S. cognitive pipeline.
 *
 * @param requestId unique request identifier
 * @param conversationId conversation identifier
 * @param timestamp event timestamp
 * @param event event name
 * @param status short status label
 * @param message human-readable event message
 * @param brain selected brain, when available
 * @param model selected model, when available
 * @param nodeId logical node being processed, when available
 * @param metadata event-specific metadata
 */
public record CognitiveEvent(
        String requestId,
        String conversationId,
        Instant timestamp,
        CognitiveEventType event,
        String status,
        String message,
        BrainType brain,
        String model,
        String nodeId,
        Map<String, Object> metadata
) {

    /**
     * Creates a cognitive event with immutable metadata.
     */
    public CognitiveEvent {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
