package com.jarvis.tools.mcp;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Placeholder client used for MCP servers that must run on a different host.
 */
public class UnavailableMcpClient implements McpClient {

    private final String serverId;
    private final String reason;

    /**
     * Creates an unavailable client.
     *
     * @param serverId server id
     * @param reason unavailable reason
     */
    public UnavailableMcpClient(String serverId, String reason) {
        this.serverId = serverId;
        this.reason = reason;
    }

    @Override
    public void initialize() {
        throw new McpException("MCP server '" + serverId + "' is unavailable: " + reason);
    }

    @Override
    public List<McpToolDescriptor> listTools() {
        return List.of();
    }

    @Override
    public McpCallResult callTool(String toolName, Map<String, Object> arguments, Duration timeout) {
        return new McpCallResult(false, List.of(), Map.of(), "MCP_UNAVAILABLE", reason);
    }

    @Override
    public McpConnectionState state() {
        return McpConnectionState.ERROR;
    }

    @Override
    public void close() {
        // Nothing to close.
    }
}
