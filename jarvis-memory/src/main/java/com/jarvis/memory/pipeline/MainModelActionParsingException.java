package com.jarvis.memory.pipeline;

/**
 * Raised when the main model returns an invalid action envelope.
 */
public class MainModelActionParsingException extends RuntimeException {

    /**
     * Creates a parsing exception.
     *
     * @param message message
     */
    public MainModelActionParsingException(String message) {
        super(message);
    }

    /**
     * Creates a parsing exception.
     *
     * @param message message
     * @param cause cause
     */
    public MainModelActionParsingException(String message, Throwable cause) {
        super(message, cause);
    }
}
