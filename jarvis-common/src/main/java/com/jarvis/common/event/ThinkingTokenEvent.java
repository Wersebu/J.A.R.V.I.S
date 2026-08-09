package com.jarvis.common.event;

import java.time.Instant;

/**
 * Internal event carrying a model reasoning-channel fragment.
 *
 * @param type event type
 * @param timestamp event timestamp
 * @param conversationId conversation identifier
 * @param text reasoning-channel text
 */
public record ThinkingTokenEvent(ChatEventType type, Instant timestamp, String conversationId, String text) implements ChatEvent {

    /**
     * Creates a thinking token event.
     *
     * @param conversationId conversation identifier
     * @param text reasoning-channel text
     * @return thinking token event
     */
    public static ThinkingTokenEvent create(String conversationId, String text) {
        return new ThinkingTokenEvent(ChatEventType.THINKING, Instant.now(), conversationId, text);
    }
}
