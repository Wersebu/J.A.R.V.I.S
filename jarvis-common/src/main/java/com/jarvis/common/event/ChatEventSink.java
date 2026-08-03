package com.jarvis.common.event;

/**
 * Transport-independent receiver for chat lifecycle events.
 */
@FunctionalInterface
public interface ChatEventSink {

    /**
     * Publishes an event to the active client transport.
     *
     * @param event chat event
     */
    void publish(ChatEvent event);
}
