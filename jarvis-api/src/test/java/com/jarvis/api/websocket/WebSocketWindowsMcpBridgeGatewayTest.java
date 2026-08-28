package com.jarvis.api.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.tools.mcp.McpAccessLevel;
import com.jarvis.tools.mcp.McpCallResult;
import com.jarvis.tools.mcp.McpException;
import com.jarvis.tools.mcp.McpExecutionHost;
import com.jarvis.tools.mcp.McpServerProperties;
import com.jarvis.tools.mcp.McpToolDescriptor;
import com.jarvis.tools.mcp.McpTransport;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression test for a race between Core's own bridge-request wait and the Windows client's
 * internal watchdog: both used to be handed the exact same {@code Duration} (e.g. {@code
 * callTimeoutMs} built from the same {@code McpServerProperties.getCallTimeout()} sent to Windows
 * in {@code MCP_CONNECT}), so Core's {@code future.get(timeout)} always fired first (its clock
 * starts before Windows even receives the request) and discarded the pending future - a real,
 * on-time Windows response then arrived to find no matching entry in {@code pending} and was
 * logged and dropped as a "stale response", never reaching the model. {@link
 * WebSocketWindowsMcpBridgeGateway} now waits {@code timeout + BRIDGE_RESPONSE_SLACK} before
 * giving up, so a response that genuinely respects the timeout it was told to use on the Windows
 * side can still be matched to its future.
 */
class WebSocketWindowsMcpBridgeGatewayTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void aResponseArrivingAfterTheRequestedTimeoutButWithinTheSlackWindowStillSucceeds() throws Exception {
        WebSocketWindowsMcpBridgeGateway gateway = new WebSocketWindowsMcpBridgeGateway(objectMapper);
        WebSocketSession session = fakeOpenSession();
        gateway.register(session);

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            respondAfterDelay(gateway, session, scheduler, 200, true);

            // Requested timeout (50ms) is far shorter than how long the simulated Windows round
            // trip actually takes (200ms) - without the slack this would throw before the response
            // ever had a chance to arrive.
            McpCallResult result = gateway.callTool("roblox", "list_roblox_studios", Map.of(), Duration.ofMillis(50));

            assertThat(result.success()).isTrue();
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void aResponseThatNeverArrivesStillTimesOutEventually() {
        WebSocketWindowsMcpBridgeGateway gateway = new WebSocketWindowsMcpBridgeGateway(objectMapper);
        WebSocketSession session = fakeOpenSession();
        gateway.register(session);

        assertThatThrownBy(() -> gateway.callTool("roblox", "list_roblox_studios", Map.of(), Duration.ofMillis(10)))
                .hasMessageContaining("timed out");
    }

    /**
     * Regression test for Stage 4: a missing/wrong-shaped {@code payload.tools} field used to be
     * silently treated as a genuinely empty (successful) discovery - {@code tools.isArray()} is
     * false for both a missing field and a malformed one, and the old code returned {@code
     * List.of()} either way. That made a real Windows-side protocol bug indistinguishable from
     * Roblox Studio simply not being attached yet, so nothing ever surfaced the actual defect.
     */
    @Test
    void listToolsRejectsAMissingToolsFieldAsAProtocolErrorInsteadOfAnEmptyList() throws Exception {
        WebSocketWindowsMcpBridgeGateway gateway = new WebSocketWindowsMcpBridgeGateway(objectMapper);
        WebSocketSession session = fakeOpenSession();
        gateway.register(session);
        respondImmediately(gateway, session, "{\"requestId\":\"%s\",\"success\":true,\"payload\":{}}");

        assertThatThrownBy(() -> gateway.listTools("roblox", windowsBridgeServer()))
                .isInstanceOf(McpException.class)
                .hasMessageContaining("malformed")
                .hasMessageContaining("roblox");
    }

    @Test
    void listToolsRejectsAToolsFieldThatIsNotAnArray() throws Exception {
        WebSocketWindowsMcpBridgeGateway gateway = new WebSocketWindowsMcpBridgeGateway(objectMapper);
        WebSocketSession session = fakeOpenSession();
        gateway.register(session);
        respondImmediately(gateway, session, "{\"requestId\":\"%s\",\"success\":true,\"payload\":{\"tools\":\"unexpected-string\"}}");

        assertThatThrownBy(() -> gateway.listTools("roblox", windowsBridgeServer()))
                .isInstanceOf(McpException.class)
                .hasMessageContaining("malformed");
    }

    @Test
    void listToolsAcceptsAGenuinelyEmptyArrayAsAValidEmptyDiscovery() throws Exception {
        WebSocketWindowsMcpBridgeGateway gateway = new WebSocketWindowsMcpBridgeGateway(objectMapper);
        WebSocketSession session = fakeOpenSession();
        gateway.register(session);
        respondImmediately(gateway, session, "{\"requestId\":\"%s\",\"success\":true,\"payload\":{\"tools\":[]}}");

        List<McpToolDescriptor> tools = gateway.listTools("roblox", windowsBridgeServer());

        assertThat(tools).isEmpty();
    }

    @Test
    void listToolsMapsRealToolDescriptorsIncludingNestedInputSchema() throws Exception {
        WebSocketWindowsMcpBridgeGateway gateway = new WebSocketWindowsMcpBridgeGateway(objectMapper);
        WebSocketSession session = fakeOpenSession();
        gateway.register(session);
        respondImmediately(gateway, session, "{\"requestId\":\"%s\",\"success\":true,\"payload\":{\"tools\":["
                + "{\"name\":\"search_game_tree\",\"description\":\"Search the open project tree\","
                + "\"inputSchema\":{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}},\"required\":[\"query\"]}}"
                + "]}}");

        List<McpToolDescriptor> tools = gateway.listTools("roblox", windowsBridgeServer());

        assertThat(tools).hasSize(1);
        assertThat(tools.getFirst().name()).isEqualTo("search_game_tree");
        assertThat(tools.getFirst().jarvisToolName()).isEqualTo("mcp_roblox_search_game_tree");
        assertThat(tools.getFirst().inputSchema()).containsKey("properties");
    }

    @Test
    void codingRequestUsesTheSameCorrelationIdRequestResponsePath() throws Exception {
        WebSocketWindowsMcpBridgeGateway gateway = new WebSocketWindowsMcpBridgeGateway(objectMapper);
        WebSocketSession session = fakeOpenSession();
        gateway.register(session);

        doAnswer(invocation -> {
            TextMessage message = invocation.getArgument(0);
            var request = objectMapper.readTree(message.getPayload());
            assertThat(request.path("type").asText()).isEqualTo("CODING_EXECUTOR_REQUEST");
            assertThat(request.path("serverId").asText()).isEqualTo("coding");
            assertThat(request.path("payload").path("operation").asText()).isEqualTo("workspace_validate");
            String requestId = request.path("requestId").asText();
            gateway.handleResponse(objectMapper.readTree("{\"requestId\":\"" + requestId + "\",\"success\":true,"
                    + "\"payload\":{\"canonicalPath\":\"D:\\\\workspace\",\"name\":\"workspace\"}}"));
            return null;
        }).when(session).sendMessage(any(TextMessage.class));

        Map<String, Object> response = gateway.codingRequest("workspace_validate", Map.of("rootPath", "D:\\workspace"), Duration.ofMillis(50));

        assertThat(response).containsEntry("canonicalPath", "D:\\workspace");
    }

    @Test
    void codingRequestFailsClearlyWhenNoBridgeIsConnected() {
        WebSocketWindowsMcpBridgeGateway gateway = new WebSocketWindowsMcpBridgeGateway(objectMapper);

        assertThatThrownBy(() -> gateway.codingRequest("workspace_validate", Map.of("rootPath", "D:\\workspace"), Duration.ofMillis(10)))
                .isInstanceOf(McpException.class)
                .hasMessageContaining("no Windows Bridge session is connected");
    }

    @Test
    void codingRequestFailsClearlyWhenMoreThanOneWindowsSessionIsConnected() {
        WebSocketWindowsMcpBridgeGateway gateway = new WebSocketWindowsMcpBridgeGateway(objectMapper);
        gateway.register(fakeOpenSession("session-1"));
        gateway.register(fakeOpenSession("session-2"));

        assertThatThrownBy(() -> gateway.codingRequest("workspace_validate", Map.of("rootPath", "D:\\workspace"), Duration.ofMillis(10)))
                .isInstanceOf(McpException.class)
                .hasMessageContaining("multiple Windows Bridge sessions");
    }

    private void respondImmediately(WebSocketWindowsMcpBridgeGateway gateway, WebSocketSession session, String payloadTemplate) throws Exception {
        doAnswer(invocation -> {
            TextMessage message = invocation.getArgument(0);
            String requestId = objectMapper.readTree(message.getPayload()).path("requestId").asText();
            gateway.handleResponse(objectMapper.readTree(String.format(payloadTemplate, requestId)));
            return null;
        }).when(session).sendMessage(any(TextMessage.class));
    }

    private McpServerProperties windowsBridgeServer() {
        McpServerProperties properties = new McpServerProperties();
        properties.setEnabled(true);
        properties.setExecutionHost(McpExecutionHost.WINDOWS);
        properties.setTransport(McpTransport.WINDOWS_BRIDGE);
        properties.setCommand("cmd.exe");
        properties.setArgs(List.of("/c", "cd /d %LOCALAPPDATA%\\Roblox && .\\mcp.bat"));
        properties.setAccessLevel(McpAccessLevel.EDIT);
        return properties;
    }

    private void respondAfterDelay(
            WebSocketWindowsMcpBridgeGateway gateway,
            WebSocketSession session,
            ScheduledExecutorService scheduler,
            long delayMs,
            boolean success
    ) throws Exception {
        doAnswer(invocation -> {
            TextMessage message = invocation.getArgument(0);
            String requestId = objectMapper.readTree(message.getPayload()).path("requestId").asText();
            scheduler.schedule(() -> {
                try {
                    gateway.handleResponse(objectMapper.readTree(
                            "{\"requestId\":\"" + requestId + "\",\"success\":" + success
                                    + ",\"payload\":{\"content\":[]}}"));
                } catch (Exception ignored) {
                    // Test-only best effort delivery.
                }
            }, delayMs, TimeUnit.MILLISECONDS);
            return null;
        }).when(session).sendMessage(any(TextMessage.class));
    }

    private WebSocketSession fakeOpenSession() {
        return fakeOpenSession("session-1");
    }

    private WebSocketSession fakeOpenSession(String id) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        when(session.getId()).thenReturn(id);
        return session;
    }
}
