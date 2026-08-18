package com.jarvis.tools.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.common.ai.NativeToolDefinition;
import com.jarvis.tools.schema.DefaultToolRegistry;
import com.jarvis.tools.runtime.NativeToolSchemaMapper;
import com.jarvis.tools.runtime.ToolIntent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WindowsBridgeMcpLifecycleTest {

    @Test
    void activatesEnabledWindowsBridgeServersAndDiscoversTools() {
        RecordingWindowsBridgeGateway gateway = new RecordingWindowsBridgeGateway(true);
        DefaultMcpServerManager manager = manager(gateway, Map.of(
                "roblox", windowsBridgeServer(true),
                "disabled", windowsBridgeServer(false),
                "core", coreServer(true)
        ));

        manager.activateWindowsBridgeServers();

        assertThat(gateway.initializedServers).containsExactly("roblox");
        assertThat(gateway.listedServers).containsExactly("roblox");
        assertThat(manager.statuses())
                .filteredOn(status -> status.serverId().equals("roblox"))
                .singleElement()
                .satisfies(status -> {
                    assertThat(status.state()).isEqualTo(McpConnectionState.CONNECTED);
                    assertThat(status.bridgeConnected()).isTrue();
                    assertThat(status.discoveredTools()).isEqualTo(1);
                    assertThat(status.lastError()).isBlank();
                });
    }

    @Test
    void dynamicMcpToolsBecomeVisibleToNativeModelSchemaAfterActivation() {
        RecordingWindowsBridgeGateway gateway = new RecordingWindowsBridgeGateway(true);
        DefaultMcpServerManager manager = manager(gateway, Map.of("roblox", windowsBridgeServer(true)));
        manager.activateWindowsBridgeServers();
        McpDynamicToolSource source = new McpDynamicToolSource(enabledProperties(Map.of()), manager);
        NativeToolSchemaMapper mapper = new NativeToolSchemaMapper(new DefaultToolRegistry(List.of(), List.of(source)));

        assertThat(mapper.definitions(ToolIntent.NO_TOOL))
                .extracting(NativeToolDefinition::name)
                .contains("mcp_roblox_read_workspace__call");
    }

    @Test
    void disconnectedBridgeDoesNotAttemptConnectOrCacheEmptyTools() {
        RecordingWindowsBridgeGateway gateway = new RecordingWindowsBridgeGateway(false);
        DefaultMcpServerManager manager = manager(gateway, Map.of("roblox", windowsBridgeServer(true)));

        manager.activateWindowsBridgeServers();

        assertThat(gateway.initializedServers).isEmpty();
        assertThat(gateway.listedServers).isEmpty();
        assertThat(manager.discoverTools()).isEmpty();
        assertThat(manager.statuses())
                .singleElement()
                .satisfies(status -> {
                    assertThat(status.state()).isEqualTo(McpConnectionState.DISCONNECTED);
                    assertThat(status.bridgeConnected()).isFalse();
                    assertThat(status.discoveredTools()).isZero();
                    assertThat(status.lastError()).contains("Windows MCP bridge is not connected");
                });
    }

    @Test
    void bridgeDisconnectClearsDiscoveredToolsAndStatus() {
        RecordingWindowsBridgeGateway gateway = new RecordingWindowsBridgeGateway(true);
        DefaultMcpServerManager manager = manager(gateway, Map.of("roblox", windowsBridgeServer(true)));
        manager.activateWindowsBridgeServers();

        gateway.connected = false;
        manager.handleWindowsBridgeDisconnected();

        assertThat(manager.statuses())
                .singleElement()
                .satisfies(status -> {
                    assertThat(status.state()).isEqualTo(McpConnectionState.DISCONNECTED);
                    assertThat(status.discoveredTools()).isZero();
                    assertThat(status.lastError()).contains("Windows MCP bridge disconnected");
                });
        assertThat(manager.discoverTools()).isEmpty();
    }

    private static DefaultMcpServerManager manager(
            RecordingWindowsBridgeGateway gateway,
            Map<String, McpServerProperties> servers
    ) {
        McpProperties properties = enabledProperties(servers);
        return new DefaultMcpServerManager(
                properties,
                new DefaultMcpClientFactory(new ObjectMapper(), "test", provider(gateway)),
                provider(gateway)
        );
    }

    private static McpProperties enabledProperties(Map<String, McpServerProperties> servers) {
        McpProperties properties = new McpProperties();
        properties.setEnabled(true);
        properties.setServers(servers);
        return properties;
    }

    private static McpServerProperties windowsBridgeServer(boolean enabled) {
        McpServerProperties properties = new McpServerProperties();
        properties.setEnabled(enabled);
        properties.setExecutionHost(McpExecutionHost.WINDOWS);
        properties.setTransport(McpTransport.WINDOWS_BRIDGE);
        properties.setCommand("cmd.exe");
        properties.setArgs(List.of("/c", "echo roblox"));
        return properties;
    }

    private static McpServerProperties coreServer(boolean enabled) {
        McpServerProperties properties = new McpServerProperties();
        properties.setEnabled(enabled);
        properties.setExecutionHost(McpExecutionHost.CORE);
        properties.setTransport(McpTransport.STDIO);
        properties.setCommand("node");
        properties.setArgs(List.of("server.js"));
        return properties;
    }

    private static ObjectProvider<WindowsMcpBridgeGateway> provider(WindowsMcpBridgeGateway gateway) {
        return new ObjectProvider<>() {
            @Override
            public WindowsMcpBridgeGateway getObject(Object... args) {
                return gateway;
            }

            @Override
            public WindowsMcpBridgeGateway getIfAvailable() {
                return gateway;
            }

            @Override
            public WindowsMcpBridgeGateway getIfUnique() {
                return gateway;
            }

            @Override
            public WindowsMcpBridgeGateway getObject() {
                return gateway;
            }
        };
    }

    private static class RecordingWindowsBridgeGateway implements WindowsMcpBridgeGateway {

        private boolean connected;
        private final List<String> initializedServers = new java.util.ArrayList<>();
        private final List<String> listedServers = new java.util.ArrayList<>();

        RecordingWindowsBridgeGateway(boolean connected) {
            this.connected = connected;
        }

        @Override
        public boolean isBridgeConnected() {
            return connected;
        }

        @Override
        public void initialize(String serverId, McpServerProperties properties, String clientVersion) {
            initializedServers.add(serverId);
        }

        @Override
        public List<McpToolDescriptor> listTools(String serverId, McpServerProperties properties) {
            listedServers.add(serverId);
            return List.of(new McpToolDescriptor(
                    serverId,
                    "read_workspace",
                    "Reads the Roblox workspace.",
                    Map.of("properties", Map.of("path", Map.of("type", "string")), "required", List.of("path")),
                    McpAccessLevel.EDIT
            ));
        }

        @Override
        public McpCallResult callTool(String serverId, String toolName, Map<String, Object> arguments, Duration timeout) {
            return new McpCallResult(true, List.of(), Map.of(), "", "");
        }

        @Override
        public void disconnect(String serverId) {
        }

        @Override
        public String bridgeStatus() {
            return connected ? "CONNECTED" : "BRIDGE_UNAVAILABLE";
        }
    }
}
