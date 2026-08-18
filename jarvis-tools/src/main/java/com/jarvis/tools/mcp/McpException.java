package com.jarvis.tools.mcp;

/**
 * Failure raised by the MCP integration layer.
 */
public class McpException extends RuntimeException {

    /**
     * Creates an MCP exception.
     *
     * @param message failure message
     */
    public McpException(String message) {
        super(message);
    }

    /**
     * Creates an MCP exception.
     *
     * @param message failure message
     * @param cause cause
     */
    public McpException(String message, Throwable cause) {
        super(message, cause);
    }
}
