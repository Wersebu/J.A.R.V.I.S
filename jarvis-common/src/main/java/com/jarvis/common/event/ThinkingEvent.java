package com.jarvis.common.event;

import java.time.Instant;

/**
 * Event emitted when the model is thinking before token generation.
 *
 * @param type event type
 * @param timestamp event timestamp
 * @param conversationId conversation identifier
 */
public record ThinkingEvent(ChatEventType type, Instant timestamp, String conversationId) implements ChatEvent {

    /**
     * Creates a thinking event.
     *
     * @param conversationId conversation identifier
     * @return event
     */
    public static ThinkingEvent create(String conversationId) {
        return new ThinkingEvent(ChatEventType.THINKING, Instant.now(), conversationId);
    }
}
