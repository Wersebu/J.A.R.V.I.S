package com.jarvis.tools.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Default MCP client factory.
 */
@Service
public class DefaultMcpClientFactory implements McpClientFactory {

    private final ObjectMapper objectMapper;
    private final String clientVersion;
    private final WindowsMcpBridgeGateway windowsBridgeGateway;

    /**
     * Creates the factory.
     *
     * @param objectMapper JSON mapper
     * @param clientVersion Jarvis version exposed to MCP servers
     */
    public DefaultMcpClientFactory(
            ObjectMapper objectMapper,
            @Value("${jarvis.version:dev}") String clientVersion,
            ObjectProvider<WindowsMcpBridgeGateway> windowsBridgeGateway
    ) {
        this.objectMapper = objectMapper;
        this.clientVersion = clientVersion;
        this.windowsBridgeGateway = windowsBridgeGateway.getIfAvailable();
    }

    @Override
    public McpClient create(String serverId, McpServerProperties properties) {
        if (properties.getExecutionHost() == McpExecutionHost.CORE && properties.getTransport() == McpTransport.STDIO) {
            return new StdioMcpClient(serverId, properties, objectMapper, clientVersion);
        }
        if (properties.getExecutionHost() == McpExecutionHost.WINDOWS && properties.getTransport() == McpTransport.WINDOWS_BRIDGE) {
            if (windowsBridgeGateway == null) {
                return new UnavailableMcpClient(serverId, "Windows MCP bridge gateway is not available in this Core build.");
            }
            return new WindowsBridgeMcpClient(serverId, properties, clientVersion, windowsBridgeGateway);
        }
        return new UnavailableMcpClient(serverId, "MCP server requires " + properties.getExecutionHost()
                + " / " + properties.getTransport() + " bridge, which is not connected yet.");
    }
}
