package com.jarvis.common.event;

import java.time.Instant;

/**
 * Event emitted for each generated token.
 *
 * @param type event type
 * @param timestamp event timestamp
 * @param conversationId conversation identifier
 * @param text token text
 */
public record TokenEvent(ChatEventType type, Instant timestamp, String conversationId, String text) implements ChatEvent {

    /**
     * Creates a token event.
     *
     * @param conversationId conversation identifier
     * @param text token text
     * @return event
     */
    public static TokenEvent create(String conversationId, String text) {
        return new TokenEvent(ChatEventType.TOKEN, Instant.now(), conversationId, text);
    }
}
