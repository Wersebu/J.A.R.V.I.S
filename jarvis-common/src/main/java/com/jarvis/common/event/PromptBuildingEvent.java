package com.jarvis.common.event;

import java.time.Instant;

/**
 * Event emitted when prompt building completes.
 *
 * @param type event type
 * @param timestamp event timestamp
 * @param conversationId conversation identifier
 * @param latencyMs prompt builder latency in milliseconds
 */
public record PromptBuildingEvent(
        ChatEventType type,
        Instant timestamp,
        String conversationId,
        long latencyMs
) implements ChatEvent {

    /**
     * Creates a prompt-building event.
     *
     * @param conversationId conversation identifier
     * @param latencyMs prompt builder latency in milliseconds
     * @return event
     */
    public static PromptBuildingEvent create(String conversationId, long latencyMs) {
        return new PromptBuildingEvent(ChatEventType.PROMPT_BUILDING, Instant.now(), conversationId, latencyMs);
    }
}
