package com.jarvis.common.ai;

/**
 * Runtime exception for AI provider failures.
 */
public class AIProviderException extends RuntimeException {

    /**
     * Creates an AI provider exception.
     *
     * @param message failure message
     */
    public AIProviderException(String message) {
        super(message);
    }

    /**
     * Creates an AI provider exception with a cause.
     *
     * @param message failure message
     * @param cause underlying cause
     */
    public AIProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
