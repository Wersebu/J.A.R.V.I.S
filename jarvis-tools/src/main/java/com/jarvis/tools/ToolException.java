package com.jarvis.tools;

/**
 * Runtime exception raised by the native tool framework.
 */
public class ToolException extends RuntimeException {

    /**
     * Creates a tool exception.
     *
     * @param message failure message
     */
    public ToolException(String message) {
        super(message);
    }

    /**
     * Creates a tool exception.
     *
     * @param message failure message
     * @param cause root cause
     */
    public ToolException(String message, Throwable cause) {
        super(message, cause);
    }
}
