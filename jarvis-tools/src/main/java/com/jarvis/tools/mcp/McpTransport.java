package com.jarvis.tools.mcp;

/**
 * Transport used to communicate with an MCP server.
 */
public enum McpTransport {
    /** JSON-RPC over a local stdio process. */
    STDIO,
    /** JSON-RPC relayed through the Windows client bridge. */
    WINDOWS_BRIDGE
}
