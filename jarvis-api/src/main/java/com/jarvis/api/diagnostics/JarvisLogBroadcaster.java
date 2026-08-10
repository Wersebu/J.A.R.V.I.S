package com.jarvis.api.diagnostics;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * In-process fan-out for backend log events sent to connected Windows clients.
 */
public final class JarvisLogBroadcaster {

    private static final CopyOnWriteArrayList<Consumer<JarvisLogEvent>> SUBSCRIBERS = new CopyOnWriteArrayList<>();

    private JarvisLogBroadcaster() {
    }

    /**
     * Subscribes a consumer to live J.A.R.V.I.S. logs.
     *
     * @param subscriber log subscriber
     * @return subscription handle
     */
    public static AutoCloseable subscribe(Consumer<JarvisLogEvent> subscriber) {
        if (subscriber == null) {
            return () -> { };
        }
        SUBSCRIBERS.add(subscriber);
        return () -> SUBSCRIBERS.remove(subscriber);
    }

    /**
     * Publishes one log event to connected subscribers.
     *
     * @param event log event
     */
    public static void publish(JarvisLogEvent event) {
        if (event == null || SUBSCRIBERS.isEmpty()) {
            return;
        }
        for (Consumer<JarvisLogEvent> subscriber : SUBSCRIBERS) {
            subscriber.accept(event);
        }
    }
}
