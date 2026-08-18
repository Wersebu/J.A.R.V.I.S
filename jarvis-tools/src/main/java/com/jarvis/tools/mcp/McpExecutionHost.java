package com.jarvis.tools.mcp;

/**
 * Machine that is responsible for running a configured MCP server.
 */
public enum McpExecutionHost {
    /** Run the MCP server from the Core process host. */
    CORE,
    /** Run the MCP server on the Windows client host through the future bridge. */
    WINDOWS
}
