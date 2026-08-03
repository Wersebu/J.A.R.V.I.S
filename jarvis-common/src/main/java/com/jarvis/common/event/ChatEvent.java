package com.jarvis.common.event;

import java.time.Instant;

/**
 * Base contract for strongly typed chat lifecycle events.
 */
public interface ChatEvent {

    /**
     * Returns the event type.
     *
     * @return event type
     */
    ChatEventType type();

    /**
     * Returns the event timestamp.
     *
     * @return event timestamp
     */
    Instant timestamp();

    /**
     * Returns the conversation identifier.
     *
     * @return conversation identifier
     */
    String conversationId();
}
