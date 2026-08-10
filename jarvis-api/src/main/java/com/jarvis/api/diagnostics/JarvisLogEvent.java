package com.jarvis.api.diagnostics;

import java.time.Instant;

/**
 * WebSocket-safe representation of one J.A.R.V.I.S. backend log line.
 *
 * @param type event type consumed by Windows debug console
 * @param timestamp log timestamp
 * @param level log level
 * @param logger logger name
 * @param thread thread name
 * @param message formatted log message
 * @param throwable optional throwable stack trace
 */
public record JarvisLogEvent(
        String type,
        Instant timestamp,
        String level,
        String logger,
        String thread,
        String message,
        String throwable
) {

    /**
     * Creates a backend log event.
     *
     * @param timestamp log timestamp
     * @param level log level
     * @param logger logger name
     * @param thread thread name
     * @param message formatted log message
     * @param throwable optional throwable stack trace
     * @return log event
     */
    public static JarvisLogEvent of(
            Instant timestamp,
            String level,
            String logger,
            String thread,
            String message,
            String throwable
    ) {
        return new JarvisLogEvent("JARVIS_LOG", timestamp, level, logger, thread, message, throwable);
    }
}
