package com.jarvis.tools.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WindowsBridgeMcpClientTest {

    @Test
    void factoryCreatesWindowsBridgeClientWhenGatewayIsAvailable() {
        DefaultMcpClientFactory factory = new DefaultMcpClientFactory(new ObjectMapper(), "test", provider(new FakeWindowsBridgeGateway(true)));
        McpClient client = factory.create("roblox", windowsBridgeServer());

        assertThat(client).isInstanceOf(WindowsBridgeMcpClient.class);
    }

    @Test
    void windowsBridgeClientDiscoversAndCallsToolsThroughGateway() {
        FakeWindowsBridgeGateway gateway = new FakeWindowsBridgeGateway(true);
        McpClient client = new WindowsBridgeMcpClient("roblox", windowsBridgeServer(), "test", gateway);

        client.initialize();
        List<McpToolDescriptor> tools = client.listTools();
        McpCallResult result = client.callTool("roblox_read", Map.of("path", "Workspace"), Duration.ofSeconds(1));

        assertThat(gateway.initialized).isTrue();
        assertThat(tools).extracting(McpToolDescriptor::name).containsExactly("roblox_read");
        assertThat(result.success()).isTrue();
        assertThat(result.text()).isEqualTo("ok");
    }

    @Test
    void windowsBridgeClientFailsWhenBridgeIsOffline() {
        McpClient client = new WindowsBridgeMcpClient("roblox", windowsBridgeServer(), "test", new FakeWindowsBridgeGateway(false));

        assertThatThrownBy(client::initialize)
                .isInstanceOf(McpException.class)
                .hasMessageContaining("Windows MCP bridge is not connected");
    }

    @Test
    void managerDoesNotCacheFailedWindowsBridgeDiscovery() {
        ToggleWindowsBridgeGateway gateway = new ToggleWindowsBridgeGateway();
        McpProperties properties = new McpProperties();
        properties.setEnabled(true);
        properties.setServers(Map.of("roblox", windowsBridgeServer()));
        DefaultMcpServerManager manager = new DefaultMcpServerManager(
                properties,
                new DefaultMcpClientFactory(new ObjectMapper(), "test", provider(gateway)),
                provider(gateway)
        );

        assertThat(manager.discoverTools()).isEmpty();

        gateway.connected = true;

        assertThat(manager.discoverTools()).extracting(McpToolDescriptor::name).containsExactly("roblox_read");
    }

    private static McpServerProperties windowsBridgeServer() {
        McpServerProperties properties = new McpServerProperties();
        properties.setEnabled(true);
        properties.setExecutionHost(McpExecutionHost.WINDOWS);
        properties.setTransport(McpTransport.WINDOWS_BRIDGE);
        properties.setCommand("cmd.exe");
        properties.setArgs(List.of("/c", "cd /d %LOCALAPPDATA%\\Roblox && .\\mcp.bat"));
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

    private static class FakeWindowsBridgeGateway implements WindowsMcpBridgeGateway {

        private final boolean connected;
        private boolean initialized;

        FakeWindowsBridgeGateway(boolean connected) {
            this.connected = connected;
        }

        @Override
        public boolean isBridgeConnected() {
            return connected;
        }

        @Override
        public void initialize(String serverId, McpServerProperties properties, String clientVersion) {
            initialized = true;
        }

        @Override
        public List<McpToolDescriptor> listTools(String serverId, McpServerProperties properties) {
            return List.of(new McpToolDescriptor(serverId, "roblox_read", "Read Roblox data", Map.of(), McpAccessLevel.EDIT));
        }

        @Override
        public McpCallResult callTool(String serverId, String toolName, Map<String, Object> arguments, Duration timeout) {
            return new McpCallResult(true, List.of(new McpContentItem("text", "ok", null, null, Map.of())), Map.of(), "", "");
        }

        @Override
        public void disconnect(String serverId) {
        }

        @Override
        public String bridgeStatus() {
            return connected ? "CONNECTED" : "BRIDGE_UNAVAILABLE";
        }
    }

    private static class ToggleWindowsBridgeGateway extends FakeWindowsBridgeGateway {

        private boolean connected;

        ToggleWindowsBridgeGateway() {
            super(false);
        }

        @Override
        public boolean isBridgeConnected() {
            return connected;
        }

        @Override
        public String bridgeStatus() {
            return connected ? "CONNECTED" : "BRIDGE_UNAVAILABLE";
        }
    }
}
