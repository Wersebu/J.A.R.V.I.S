package com.jarvis.tools.web;

/**
 * Controlled web search failure.
 */
public class WebSearchException extends RuntimeException {

    private final int statusCode;

    /**
     * Creates an exception without an HTTP status code.
     *
     * @param message diagnostic message
     */
    public WebSearchException(String message) {
        super(message);
        this.statusCode = -1;
    }

    /**
     * Creates an exception without an HTTP status code.
     *
     * @param message diagnostic message
     * @param cause root cause
     */
    public WebSearchException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = -1;
    }

    /**
     * Creates an exception carrying the originating HTTP status code.
     *
     * @param message diagnostic message
     * @param statusCode HTTP status code returned by the remote server
     */
    public WebSearchException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    /**
     * Returns the originating HTTP status code, or -1 when the failure was not a plain HTTP response
     * (e.g. connection error, timeout, invalid URL).
     *
     * @return HTTP status code, or -1 when unknown
     */
    public int statusCode() {
        return statusCode;
    }
}
