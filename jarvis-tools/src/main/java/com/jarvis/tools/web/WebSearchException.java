package com.jarvis.tools.web;

/**
 * Controlled web search failure.
 */
public class WebSearchException extends RuntimeException {

    /**
     * Creates an exception.
     *
     * @param message diagnostic message
     */
    public WebSearchException(String message) {
        super(message);
    }

    /**
     * Creates an exception.
     *
     * @param message diagnostic message
     * @param cause root cause
     */
    public WebSearchException(String message, Throwable cause) {
        super(message, cause);
    }
}
