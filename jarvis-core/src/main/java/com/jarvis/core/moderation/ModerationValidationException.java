package com.jarvis.core.moderation;

/**
 * Safe request validation failure for the TopkiMC moderation API.
 */
public class ModerationValidationException extends IllegalArgumentException {

    public ModerationValidationException(String message) {
        super(message);
    }
}
