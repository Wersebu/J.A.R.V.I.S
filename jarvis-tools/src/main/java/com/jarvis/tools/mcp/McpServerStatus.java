package com.jarvis.tools.mcp;

/**
 * Public status of one MCP server.
 *
 * @param serverId server id
 * @param enabled whether the server is enabled
 * @param executionHost configured execution host
 * @param transport configured transport
 * @param state connection state
 * @param discoveredTools number of discovered tools
 * @param lastError last error message
 */
public record McpServerStatus(
        String serverId,
        boolean enabled,
        McpExecutionHost executionHost,
        McpTransport transport,
        McpConnectionState state,
        int discoveredTools,
        String lastError
) {
}
