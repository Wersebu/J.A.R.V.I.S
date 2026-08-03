package com.jarvis.knowledge;

/**
 * Runtime exception for knowledge engine failures.
 */
public class KnowledgeException extends RuntimeException {

    /**
     * Creates a knowledge exception.
     *
     * @param message failure message
     */
    public KnowledgeException(String message) {
        super(message);
    }

    /**
     * Creates a knowledge exception.
     *
     * @param message failure message
     * @param cause underlying cause
     */
    public KnowledgeException(String message, Throwable cause) {
        super(message, cause);
    }
}
