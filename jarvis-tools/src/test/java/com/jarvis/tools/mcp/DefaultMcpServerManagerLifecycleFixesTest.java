package com.jarvis.tools.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for the three MCP discovery-lifecycle bugs found while diagnosing why a real
 * Roblox Studio MCP connection could go {@code connecting -> connected} and then still end up
 * discovering 0 tools:
 * <ol>
 *     <li>{@code activateWindowsBridgeServers} used to drop the previous {@link McpClient} without
 *     closing it, so the Windows bridge never received {@code MCP_DISCONNECT} on a reconnect and
 *     could leave its own MCP process orphaned;</li>
 *     <li>{@code shouldRediscover} used to treat a CONNECTED server with a genuinely empty {@code
 *     tools/list} result as final, caching "0 tools" forever with no further retries;</li>
 *     <li>a manual reconnect had no way to clear that bounded-retry bookkeeping once exhausted.</li>
 * </ol>
 */
class DefaultMcpServerManagerLifecycleFixesTest {

    @Test
    void activateWindowsBridgeServersClosesThePreviousClientBeforeReplacingIt() {
        ScriptedWindowsBridgeGateway gateway = new ScriptedWindowsBridgeGateway(true);
        gateway.scriptedToolLists.add(List.of(toolDescriptor("read_workspace")));
        gateway.scriptedToolLists.add(List.of(toolDescriptor("read_workspace")));
        DefaultMcpServerManager manager = manager(gateway, Map.of("roblox", windowsBridgeServer(true)));

        manager.activateWindowsBridgeServers();
        assertThat(gateway.disconnectedServers).isEmpty();

        // Simulates a second Windows bridge registration (e.g. the WebSocket reconnected) while a
        // client for "roblox" from the first activation is still registered.
        manager.activateWindowsBridgeServers();

        assertThat(gateway.disconnectedServers).containsExactly("roblox");
        assertThat(manager.statuses())
                .filteredOn(status -> status.serverId().equals("roblox"))
                .singleElement()
                .satisfies(status -> assertThat(status.discoveredTools()).isEqualTo(1));
    }

    @Test
    void emptyButConnectedDiscoveryIsNotRetriedImmediatelyWithinTheBackoffWindow() {
        ScriptedWindowsBridgeGateway gateway = new ScriptedWindowsBridgeGateway(true);
        DefaultMcpServerManager manager = manager(gateway, Map.of("roblox", windowsBridgeServer(true)));

        assertThat(manager.discoverTools()).isEmpty();
        assertThat(manager.discoverTools()).isEmpty();

        assertThat(gateway.listToolsCallCount).isEqualTo(1);
    }

    @Test
    void emptyButConnectedDiscoveryStopsAfterBoundedRetriesWithAClearError() throws InterruptedException {
        ScriptedWindowsBridgeGateway gateway = new ScriptedWindowsBridgeGateway(true);
        DefaultMcpServerManager manager = manager(gateway, Map.of("roblox", windowsBridgeServer(true)));

        for (int attempt = 0; attempt < 7; attempt++) {
            manager.discoverTools();
            Thread.sleep(1_100L);
        }

        assertThat(gateway.listToolsCallCount).isEqualTo(5);
        assertThat(manager.statuses())
                .filteredOn(status -> status.serverId().equals("roblox"))
                .singleElement()
                .satisfies(status -> {
                    assertThat(status.state()).isEqualTo(McpConnectionState.CONNECTED);
                    assertThat(status.discoveredTools()).isZero();
                    assertThat(status.lastError()).contains("0 tools after 5 attempts");
                });
    }

    @Test
    void manualConnectResetsAnExhaustedEmptyDiscoveryBound() throws InterruptedException {
        ScriptedWindowsBridgeGateway gateway = new ScriptedWindowsBridgeGateway(true);
        DefaultMcpServerManager manager = manager(gateway, Map.of("roblox", windowsBridgeServer(true)));

        for (int attempt = 0; attempt < 7; attempt++) {
            manager.discoverTools();
            Thread.sleep(1_100L);
        }
        assertThat(gateway.listToolsCallCount).isEqualTo(5);

        gateway.scriptedToolLists.add(List.of(toolDescriptor("read_workspace")));
        manager.connect("roblox");
        List<McpToolDescriptor> tools = manager.discoverTools();

        assertThat(tools).extracting(McpToolDescriptor::name).containsExactly("read_workspace");
        assertThat(gateway.listToolsCallCount).isEqualTo(6);
    }

    private static McpToolDescriptor toolDescriptor(String name) {
        return new McpToolDescriptor("roblox", name, "Test tool", Map.of(), McpAccessLevel.READ);
    }

    private static DefaultMcpServerManager manager(
            ScriptedWindowsBridgeGateway gateway,
            Map<String, McpServerProperties> servers
    ) {
        McpProperties properties = new McpProperties();
        properties.setEnabled(true);
        properties.setServers(servers);
        return new DefaultMcpServerManager(
                properties,
                new DefaultMcpClientFactory(new ObjectMapper(), "test", provider(gateway)),
                provider(gateway)
        );
    }

    private static McpServerProperties windowsBridgeServer(boolean enabled) {
        McpServerProperties properties = new McpServerProperties();
        properties.setEnabled(enabled);
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

    /**
     * Fake bridge gateway whose {@code listTools} outcomes are scripted per-call (defaulting to an
     * empty list once the script is drained), and which records every {@code disconnect} call so
     * tests can prove a previous client was actually closed rather than just dropped.
     */
    private static final class ScriptedWindowsBridgeGateway implements WindowsMcpBridgeGateway {

        private boolean connected;
        private final Deque<List<McpToolDescriptor>> scriptedToolLists = new ArrayDeque<>();
        private final List<String> disconnectedServers = new ArrayList<>();
        private int listToolsCallCount;

        ScriptedWindowsBridgeGateway(boolean connected) {
            this.connected = connected;
        }

        @Override
        public boolean isBridgeConnected() {
            return connected;
        }

        @Override
        public void initialize(String serverId, McpServerProperties properties, String clientVersion) {
        }

        @Override
        public List<McpToolDescriptor> listTools(String serverId, McpServerProperties properties) {
            listToolsCallCount++;
            return scriptedToolLists.isEmpty() ? List.of() : scriptedToolLists.poll();
        }

        @Override
        public McpCallResult callTool(String serverId, String toolName, Map<String, Object> arguments, Duration timeout) {
            return new McpCallResult(true, List.of(), Map.of(), "", "");
        }

        @Override
        public void disconnect(String serverId) {
            disconnectedServers.add(serverId);
        }

        @Override
        public String bridgeStatus() {
            return connected ? "CONNECTED" : "BRIDGE_UNAVAILABLE";
        }
    }
}
