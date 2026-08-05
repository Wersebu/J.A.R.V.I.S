package com.jarvis.memory.research;

/**
 * Raised when a research action cannot be parsed or validated.
 */
public class ResearchActionParseException extends RuntimeException {

    /**
     * Creates a parse exception.
     *
     * @param message failure message
     */
    public ResearchActionParseException(String message) {
        super(message);
    }

    /**
     * Creates a parse exception.
     *
     * @param message failure message
     * @param cause cause
     */
    public ResearchActionParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
