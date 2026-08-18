package com.jarvis.tools.mcp;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Gateway used by Core-side MCP clients to communicate with MCP servers hosted by Windows.
 */
public interface WindowsMcpBridgeGateway {

    /**
     * Returns whether a Windows bridge session is currently connected.
     *
     * @return true when the bridge is connected
     */
    boolean isBridgeConnected();

    /**
     * Initializes one MCP server through the connected Windows bridge.
     *
     * @param serverId server id
     * @param properties server configuration
     * @param clientVersion Jarvis client version sent during the MCP handshake
     */
    void initialize(String serverId, McpServerProperties properties, String clientVersion);

    /**
     * Lists tools exposed by a Windows-hosted MCP server.
     *
     * @param serverId server id
     * @param properties server configuration
     * @return discovered tools
     */
    List<McpToolDescriptor> listTools(String serverId, McpServerProperties properties);

    /**
     * Calls one tool on a Windows-hosted MCP server.
     *
     * @param serverId server id
     * @param toolName MCP-native tool name
     * @param arguments tool arguments
     * @param timeout call timeout
     * @return call result
     */
    McpCallResult callTool(String serverId, String toolName, Map<String, Object> arguments, Duration timeout);

    /**
     * Disconnects one Windows-hosted MCP server.
     *
     * @param serverId server id
     */
    void disconnect(String serverId);

    /**
     * Returns a compact bridge status string.
     *
     * @return status
     */
    String bridgeStatus();
}
