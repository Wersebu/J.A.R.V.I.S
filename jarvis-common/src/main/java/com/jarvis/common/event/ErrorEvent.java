package com.jarvis.common.event;

import java.time.Instant;

/**
 * Event emitted when request processing fails.
 *
 * @param type event type
 * @param timestamp event timestamp
 * @param conversationId conversation identifier
 * @param message safe error message
 */
public record ErrorEvent(
        ChatEventType type,
        Instant timestamp,
        String conversationId,
        String message
) implements ChatEvent {

    /**
     * Creates an error event.
     *
     * @param conversationId conversation identifier
     * @param message safe error message
     * @return event
     */
    public static ErrorEvent create(String conversationId, String message) {
        return new ErrorEvent(ChatEventType.ERROR, Instant.now(), conversationId, message);
    }
}
