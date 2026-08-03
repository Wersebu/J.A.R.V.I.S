package com.jarvis.ollama;

/**
 * Runtime exception for Ollama communication failures.
 */
public class OllamaException extends RuntimeException {

    /**
     * Creates an Ollama exception.
     *
     * @param message failure message
     */
    public OllamaException(String message) {
        super(message);
    }

    /**
     * Creates an Ollama exception with a cause.
     *
     * @param message failure message
     * @param cause underlying cause
     */
    public OllamaException(String message, Throwable cause) {
        super(message, cause);
    }
}
