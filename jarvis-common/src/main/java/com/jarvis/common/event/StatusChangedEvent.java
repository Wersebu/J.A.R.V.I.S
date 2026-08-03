package com.jarvis.common.event;

import java.time.Instant;

/**
 * Event emitted when request processing status changes.
 *
 * @param type event type
 * @param timestamp event timestamp
 * @param conversationId conversation identifier
 * @param status status label
 */
public record StatusChangedEvent(
        ChatEventType type,
        Instant timestamp,
        String conversationId,
        String status
) implements ChatEvent {

    /**
     * Creates a status changed event.
     *
     * @param type event type
     * @param conversationId conversation identifier
     * @param status status label
     * @return event
     */
    public static StatusChangedEvent create(ChatEventType type, String conversationId, String status) {
        return new StatusChangedEvent(type, Instant.now(), conversationId, status);
    }
}
