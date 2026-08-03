package com.jarvis.common.event;

import com.jarvis.common.ai.BrainType;

import java.time.Instant;

/**
 * Event emitted when a brain is selected for a request.
 *
 * @param type event type
 * @param timestamp event timestamp
 * @param conversationId conversation identifier
 * @param brain selected logical brain
 * @param model selected model
 * @param reason selection reason
 * @param latencyMs router latency in milliseconds
 */
public record BrainSelectedEvent(
        ChatEventType type,
        Instant timestamp,
        String conversationId,
        BrainType brain,
        String model,
        String reason,
        long latencyMs
) implements ChatEvent {

    /**
     * Creates a brain-selected event.
     *
     * @param conversationId conversation identifier
     * @param brain selected logical brain
     * @param model selected model
     * @param reason selection reason
     * @param latencyMs router latency in milliseconds
     * @return event
     */
    public static BrainSelectedEvent create(
            String conversationId,
            BrainType brain,
            String model,
            String reason,
            long latencyMs
    ) {
        return new BrainSelectedEvent(ChatEventType.BRAIN_ROUTING, Instant.now(), conversationId, brain, model, reason, latencyMs);
    }
}
