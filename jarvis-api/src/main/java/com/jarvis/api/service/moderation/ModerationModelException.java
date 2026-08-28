package com.jarvis.api.service.moderation;

/**
 * Failure from the low-level moderation model boundary.
 */
public class ModerationModelException extends RuntimeException {

    public ModerationModelException(String message) {
        super(message);
    }

    public ModerationModelException(String message, Throwable cause) {
        super(message, cause);
    }
}
