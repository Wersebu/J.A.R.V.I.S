package com.jarvis.common.event;

import java.time.Instant;

/**
 * Event emitted when a chat request enters the backend.
 *
 * @param type event type
 * @param timestamp event timestamp
 * @param conversationId conversation identifier
 */
public record RequestReceivedEvent(ChatEventType type, Instant timestamp, String conversationId) implements ChatEvent {

    /**
     * Creates a request-received event.
     *
     * @param conversationId conversation identifier
     * @return event
     */
    public static RequestReceivedEvent create(String conversationId) {
        return new RequestReceivedEvent(ChatEventType.REQUEST_RECEIVED, Instant.now(), conversationId);
    }
}
