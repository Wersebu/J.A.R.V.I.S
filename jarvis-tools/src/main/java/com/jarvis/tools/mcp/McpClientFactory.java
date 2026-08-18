package com.jarvis.tools.mcp;

/**
 * Creates MCP clients for configured servers.
 */
public interface McpClientFactory {

    /**
     * Creates a client for one server.
     *
     * @param serverId server id
     * @param properties server configuration
     * @return client
     */
    McpClient create(String serverId, McpServerProperties properties);
}
