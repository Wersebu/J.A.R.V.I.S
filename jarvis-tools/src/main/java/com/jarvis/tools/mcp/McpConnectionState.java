package com.jarvis.tools.mcp;

/**
 * Lifecycle state of one configured MCP server connection.
 */
public enum McpConnectionState {
    /** The server is not connected. */
    DISCONNECTED,
    /** Connection startup is in progress. */
    CONNECTING,
    /** The server is initialized and ready. */
    CONNECTED,
    /** The server is unavailable or the connection is broken. */
    ERROR,
    /** Connection shutdown is in progress. */
    STOPPING
}
