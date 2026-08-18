package com.jarvis.tools.mcp;

import com.jarvis.tools.ToolRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default MCP server manager.
 */
@Service
public class DefaultMcpServerManager implements McpServerManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultMcpServerManager.class);

    private final McpProperties properties;
    private final McpClientFactory clientFactory;
    private final WindowsMcpBridgeGateway windowsBridgeGateway;
    private final Map<String, McpClient> clients = new ConcurrentHashMap<>();
    private final Map<String, List<McpToolDescriptor>> discoveredTools = new ConcurrentHashMap<>();
    private final Map<String, McpConnectionState> states = new ConcurrentHashMap<>();
    private final Map<String, String> lastErrors = new ConcurrentHashMap<>();

    /**
     * Creates the manager.
     *
     * @param properties MCP properties
     * @param clientFactory MCP client factory
     */
    public DefaultMcpServerManager(
            McpProperties properties,
            McpClientFactory clientFactory,
            ObjectProvider<WindowsMcpBridgeGateway> windowsBridgeGateway
    ) {
        this.properties = properties;
        this.clientFactory = clientFactory;
        this.windowsBridgeGateway = windowsBridgeGateway.getIfAvailable();
    }

    @Override
    public void connect(String serverId) {
        McpServerProperties server = server(serverId);
        if (!properties.isEnabled() || !server.isEnabled()) {
            states.put(serverId, McpConnectionState.DISCONNECTED);
            return;
        }
        try {
            LOGGER.info("[MCP] connecting server={} host={} transport={}", serverId, server.getExecutionHost(), server.getTransport());
            states.put(serverId, McpConnectionState.CONNECTING);
            McpClient client = clients.computeIfAbsent(serverId, id -> clientFactory.create(id, server));
            client.initialize();
            states.put(serverId, McpConnectionState.CONNECTED);
            lastErrors.remove(serverId);
            LOGGER.info("[MCP] connected server={}", serverId);
        } catch (RuntimeException ex) {
            states.put(serverId, McpConnectionState.ERROR);
            lastErrors.put(serverId, ex.getMessage());
            LOGGER.warn("[MCP] connection failed server={} error={}", serverId, ex.getMessage());
        }
    }

    @Override
    public void disconnect(String serverId) {
        McpClient client = clients.remove(serverId);
        if (client != null) {
            LOGGER.info("[MCP] disconnecting server={}", serverId);
            client.close();
        }
        discoveredTools.remove(serverId);
        states.put(serverId, McpConnectionState.DISCONNECTED);
        LOGGER.info("[MCP] disconnected server={}", serverId);
    }

    @Override
    public List<McpServerStatus> statuses() {
        List<McpServerStatus> statuses = new ArrayList<>();
        for (Map.Entry<String, McpServerProperties> entry : properties.getServers().entrySet()) {
            String serverId = entry.getKey();
            McpServerProperties server = entry.getValue();
            statuses.add(new McpServerStatus(
                    serverId,
                    properties.isEnabled() && server.isEnabled(),
                    server.getExecutionHost(),
                    server.getTransport(),
                    states.getOrDefault(serverId, McpConnectionState.DISCONNECTED),
                    bridgeConnected(server),
                    discoveredTools.getOrDefault(serverId, List.of()).size(),
                    lastErrors.getOrDefault(serverId, "")
            ));
        }
        return List.copyOf(statuses);
    }

    @Override
    public List<McpToolDescriptor> discoverTools() {
        if (!properties.isEnabled()) {
            return List.of();
        }
        List<McpToolDescriptor> result = new ArrayList<>();
        for (Map.Entry<String, McpServerProperties> entry : properties.getServers().entrySet()) {
            String serverId = entry.getKey();
            McpServerProperties server = entry.getValue();
            if (!server.isEnabled()) {
                continue;
            }
            List<McpToolDescriptor> serverTools = discoveredTools.get(serverId);
            if (serverTools == null || shouldRediscover(serverId, serverTools)) {
                serverTools = discoverServerTools(serverId, server);
                if (states.getOrDefault(serverId, McpConnectionState.DISCONNECTED) == McpConnectionState.CONNECTED) {
                    discoveredTools.put(serverId, serverTools);
                } else {
                    discoveredTools.remove(serverId);
                }
            }
            result.addAll(serverTools);
        }
        return List.copyOf(result);
    }

    @Override
    public McpCallResult call(McpToolDescriptor descriptor, ToolRequest request) {
        McpServerProperties server = server(descriptor.serverId());
        connect(descriptor.serverId());
        if (states.getOrDefault(descriptor.serverId(), McpConnectionState.ERROR) != McpConnectionState.CONNECTED) {
            return new McpCallResult(false, List.of(), Map.of(), "MCP_NOT_CONNECTED", lastErrors.getOrDefault(descriptor.serverId(), "MCP server is not connected."));
        }
        LOGGER.info("[MCP] call server={} tool={} requestId={}", descriptor.serverId(), descriptor.name(), request.requestId());
        McpCallResult result = clients.get(descriptor.serverId()).callTool(descriptor.name(), request.arguments(), server.getCallTimeout());
        LOGGER.info("[MCP] completed server={} tool={} success={}", descriptor.serverId(), descriptor.name(), result.success());
        return result;
    }

    private List<McpToolDescriptor> discoverServerTools(String serverId, McpServerProperties server) {
        connect(serverId);
        if (states.getOrDefault(serverId, McpConnectionState.ERROR) != McpConnectionState.CONNECTED) {
            return List.of();
        }
        try {
            List<McpToolDescriptor> tools = clients.get(serverId).listTools();
            LOGGER.info("[MCP] discovered server={} tools={}", serverId, tools.size());
            return List.copyOf(tools);
        } catch (RuntimeException ex) {
            states.put(serverId, McpConnectionState.ERROR);
            lastErrors.put(serverId, ex.getMessage());
            LOGGER.warn("[MCP] discovery failed server={} error={}", serverId, ex.getMessage());
            return List.of();
        }
    }

    private McpServerProperties server(String serverId) {
        McpServerProperties server = properties.getServers().get(serverId);
        if (server == null) {
            throw new McpException("Unknown MCP server: " + serverId);
        }
        return server;
    }

    private boolean bridgeConnected(McpServerProperties server) {
        return server.getExecutionHost() == McpExecutionHost.WINDOWS
                && server.getTransport() == McpTransport.WINDOWS_BRIDGE
                && windowsBridgeGateway != null
                && windowsBridgeGateway.isBridgeConnected();
    }

    private boolean shouldRediscover(String serverId, List<McpToolDescriptor> serverTools) {
        return serverTools.isEmpty()
                && states.getOrDefault(serverId, McpConnectionState.DISCONNECTED) != McpConnectionState.CONNECTED;
    }
}
