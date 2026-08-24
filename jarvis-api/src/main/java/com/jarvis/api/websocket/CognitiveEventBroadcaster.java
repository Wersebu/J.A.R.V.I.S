package com.jarvis.api.websocket;

import com.jarvis.common.event.CognitiveEvent;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Fans out {@link CognitiveEvent}s that originate outside an active request's own SSE/WebSocket
 * sink (background jobs, async title generation, scheduled memory work) to every connected
 * WebSocket session, mirroring {@link com.jarvis.api.diagnostics.JarvisLogBroadcaster}.
 */
public final class CognitiveEventBroadcaster {

    private static final CopyOnWriteArrayList<Consumer<CognitiveEvent>> SUBSCRIBERS = new CopyOnWriteArrayList<>();

    private CognitiveEventBroadcaster() {
    }

    /**
     * Subscribes to background cognitive events.
     *
     * @param subscriber receiver invoked for each published event
     * @return handle that removes the subscription when closed
     */
    public static AutoCloseable subscribe(Consumer<CognitiveEvent> subscriber) {
        if (subscriber == null) {
            return () -> { };
        }
        SUBSCRIBERS.add(subscriber);
        return () -> SUBSCRIBERS.remove(subscriber);
    }

    /**
     * Publishes a background cognitive event to every current subscriber.
     *
     * @param event event to deliver
     */
    public static void publish(CognitiveEvent event) {
        if (event == null || SUBSCRIBERS.isEmpty()) {
            return;
        }
        for (Consumer<CognitiveEvent> subscriber : SUBSCRIBERS) {
            subscriber.accept(event);
        }
    }
}
