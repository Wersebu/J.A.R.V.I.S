package com.jarvis.tools.mcp;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * MCP client adapter for servers hosted by the Windows desktop bridge.
 */
public class WindowsBridgeMcpClient implements McpClient {

    private final String serverId;
    private final McpServerProperties properties;
    private final String clientVersion;
    private final WindowsMcpBridgeGateway gateway;
    private volatile McpConnectionState state = McpConnectionState.DISCONNECTED;

    /**
     * Creates a Windows bridge MCP client.
     *
     * @param serverId server id
     * @param properties server configuration
     * @param clientVersion Jarvis version sent during initialize
     * @param gateway bridge gateway
     */
    public WindowsBridgeMcpClient(
            String serverId,
            McpServerProperties properties,
            String clientVersion,
            WindowsMcpBridgeGateway gateway
    ) {
        this.serverId = serverId;
        this.properties = properties;
        this.clientVersion = clientVersion;
        this.gateway = gateway;
    }

    @Override
    public synchronized void initialize() {
        if (state == McpConnectionState.CONNECTED) {
            return;
        }
        if (!gateway.isBridgeConnected()) {
            state = McpConnectionState.ERROR;
            throw new McpException("Windows MCP bridge is not connected.");
        }
        state = McpConnectionState.CONNECTING;
        try {
            gateway.initialize(serverId, properties, clientVersion);
            state = McpConnectionState.CONNECTED;
        } catch (RuntimeException exception) {
            state = McpConnectionState.ERROR;
            throw exception;
        }
    }

    @Override
    public List<McpToolDescriptor> listTools() {
        ensureConnected();
        return gateway.listTools(serverId, properties);
    }

    @Override
    public McpCallResult callTool(String toolName, Map<String, Object> arguments, Duration timeout) {
        ensureConnected();
        return gateway.callTool(serverId, toolName, arguments, timeout == null ? properties.getCallTimeout() : timeout);
    }

    @Override
    public McpConnectionState state() {
        return state;
    }

    @Override
    public synchronized void close() {
        if (state == McpConnectionState.DISCONNECTED) {
            return;
        }
        state = McpConnectionState.STOPPING;
        gateway.disconnect(serverId);
        state = McpConnectionState.DISCONNECTED;
    }

    private void ensureConnected() {
        if (state != McpConnectionState.CONNECTED) {
            initialize();
        }
    }
}
