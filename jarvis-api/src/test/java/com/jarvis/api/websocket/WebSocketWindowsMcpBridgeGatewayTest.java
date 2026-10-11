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

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void codingFailurePreservesWindowsErrorCodeAndMessage() {
        var gateway = new WebSocketWindowsMcpBridgeGateway(objectMapper);
        var session = fakeOpenSession();
        gateway.register(session);
        respondImmediately(gateway, session, "{\"requestId\":\"%s\",\"success\":false,"
                + "\"errorCode\":\"SEARCH_LIMIT\",\"errorMessage\":\"Narrow path; results incomplete\"}");
        assertThatThrownBy(() -> gateway.codingRequest("file_search", Map.of(), Duration.ofSeconds(1)))
                .isInstanceOf(McpException.class).hasMessageContaining("SEARCH_LIMIT")
                .hasMessageContaining("Narrow path; results incomplete");
    }

    @Test
    void aResponseArrivingAfterTheRequestedTimeoutButWithinTheSlackWindowStillSucceeds() throws Exception {
        WebSocketWindowsMcpBridgeGateway gateway = new WebSocketWindowsMcpBridgeGateway(objectMapper);
        TestWebSocketSession session = fakeOpenSession();
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
        TestWebSocketSession session = fakeOpenSession();
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
        TestWebSocketSession session = fakeOpenSession();
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
        TestWebSocketSession session = fakeOpenSession();
        gateway.register(session);
        respondImmediately(gateway, session, "{\"requestId\":\"%s\",\"success\":true,\"payload\":{\"tools\":\"unexpected-string\"}}");

        assertThatThrownBy(() -> gateway.listTools("roblox", windowsBridgeServer()))
                .isInstanceOf(McpException.class)
                .hasMessageContaining("malformed");
    }

    @Test
    void listToolsAcceptsAGenuinelyEmptyArrayAsAValidEmptyDiscovery() throws Exception {
        WebSocketWindowsMcpBridgeGateway gateway = new WebSocketWindowsMcpBridgeGateway(objectMapper);
        TestWebSocketSession session = fakeOpenSession();
        gateway.register(session);
        respondImmediately(gateway, session, "{\"requestId\":\"%s\",\"success\":true,\"payload\":{\"tools\":[]}}");

        List<McpToolDescriptor> tools = gateway.listTools("roblox", windowsBridgeServer());

        assertThat(tools).isEmpty();
    }

    @Test
    void listToolsMapsRealToolDescriptorsIncludingNestedInputSchema() throws Exception {
        WebSocketWindowsMcpBridgeGateway gateway = new WebSocketWindowsMcpBridgeGateway(objectMapper);
        TestWebSocketSession session = fakeOpenSession();
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
        TestWebSocketSession session = fakeOpenSession();
        gateway.register(session);

        session.onSend(sentMessage -> {
            TextMessage message = (TextMessage) sentMessage;
            try {
                var request = objectMapper.readTree(message.getPayload());
                assertThat(request.path("type").asText()).isEqualTo("CODING_EXECUTOR_REQUEST");
                assertThat(request.path("serverId").asText()).isEqualTo("coding");
                assertThat(request.path("payload").path("operation").asText()).isEqualTo("workspace_validate");
                String requestId = request.path("requestId").asText();
                gateway.handleResponse(objectMapper.readTree("{\"requestId\":\"" + requestId + "\",\"success\":true,"
                        + "\"payload\":{\"canonicalPath\":\"D:\\\\workspace\",\"name\":\"workspace\"}}"));
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
        });

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

    @Test
    void longRunningRequestSurvivesReconnectOfTheSameWindowsInstance() throws Exception {
        WebSocketWindowsMcpBridgeGateway gateway = new WebSocketWindowsMcpBridgeGateway(objectMapper);
        TestWebSocketSession first = fakeOpenSession("session-1");
        gateway.register(first, "instance-A");
        java.util.concurrent.atomic.AtomicReference<String> requestId = new java.util.concurrent.atomic.AtomicReference<>();
        first.onSend(message -> {
            try {
                requestId.set(objectMapper.readTree(((TextMessage) message).getPayload()).path("requestId").asText());
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
        });
        java.util.concurrent.CompletableFuture<Map<String, Object>> result = java.util.concurrent.CompletableFuture.supplyAsync(
                () -> gateway.codingRequest("build_run", Map.of("rootPath", "D:\\w"), Duration.ofSeconds(5)));
        long deadline = System.currentTimeMillis() + 2_000;
        while (requestId.get() == null && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }

        first.close();
        gateway.detach(first);
        TestWebSocketSession second = fakeOpenSession("session-2");
        gateway.register(second, "instance-A");
        gateway.handleResponse(objectMapper.readTree("{\"requestId\":\"" + requestId.get()
                + "\",\"success\":true,\"payload\":{\"exitCode\":0}}"));

        assertThat(result.get(2, TimeUnit.SECONDS)).containsEntry("exitCode", 0);
    }

    @Test
    void differentWindowsInstanceFailsPendingRequestsImmediately() throws Exception {
        WebSocketWindowsMcpBridgeGateway gateway = new WebSocketWindowsMcpBridgeGateway(objectMapper);
        TestWebSocketSession first = fakeOpenSession("session-1");
        gateway.register(first, "instance-A");
        java.util.concurrent.CompletableFuture<Map<String, Object>> result = java.util.concurrent.CompletableFuture.supplyAsync(
                () -> gateway.codingRequest("build_run", Map.of("rootPath", "D:\\w"), Duration.ofSeconds(5)));
        long deadline = System.currentTimeMillis() + 2_000;
        while (first.sentMessages().isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }
        first.close();
        gateway.detach(first);
        gateway.register(fakeOpenSession("session-2"), "instance-B");

        assertThatThrownBy(() -> result.get(2, TimeUnit.SECONDS)).hasMessageContaining("replaced");
    }

    @Test
    void eachUserReachesOnlyTheirOwnPc() {
        WebSocketWindowsMcpBridgeGateway gateway = new WebSocketWindowsMcpBridgeGateway(objectMapper);
        TestWebSocketSession ownerPc = userSession("pc-owner", "owner");
        gateway.register(ownerPc, "instance-owner");
        respondImmediately(gateway, ownerPc, "{\"requestId\":\"%s\",\"success\":true,\"payload\":{\"pc\":\"owner\"}}");

        java.util.concurrent.atomic.AtomicReference<Map<String, Object>> ownerResult = new java.util.concurrent.atomic.AtomicReference<>();
        com.jarvis.common.auth.CurrentUserContext.runAs("owner", () ->
                ownerResult.set(gateway.codingRequest("pc_info", Map.of(), Duration.ofSeconds(1))));
        assertThat(ownerResult.get()).containsEntry("pc", "owner");

        // Another account without a PC must never be routed to the owner's machine.
        com.jarvis.common.auth.CurrentUserContext.runAs("mom", () -> {
            assertThat(gateway.codingStatus()).isEqualTo("WINDOWS_BRIDGE_UNAVAILABLE");
            assertThat(gateway.isBridgeConnected()).isFalse();
            assertThatThrownBy(() -> gateway.codingRequest("pc_read", Map.of("path", "C:\secret.txt"), Duration.ofMillis(50)))
                    .isInstanceOf(McpException.class).hasMessageContaining("no Windows Bridge session");
            assertThatThrownBy(() -> gateway.callTool("roblox", "search_game_tree", Map.of(), Duration.ofMillis(50)))
                    .isInstanceOf(McpException.class).hasMessageContaining("for this user");
        });
        assertThat(ownerPc.sentMessages()).hasSize(1);
    }

    @Test
    void twoUsersWithTheirOwnPcsAreNotMistakenForMultipleSessions() {
        WebSocketWindowsMcpBridgeGateway gateway = new WebSocketWindowsMcpBridgeGateway(objectMapper);
        TestWebSocketSession ownerPc = userSession("pc-owner", "owner");
        TestWebSocketSession momPc = userSession("pc-mom", "mom");
        gateway.register(ownerPc, "instance-owner");
        gateway.register(momPc, "instance-mom");
        respondImmediately(gateway, momPc, "{\"requestId\":\"%s\",\"success\":true,\"payload\":{\"pc\":\"mom\"}}");

        com.jarvis.common.auth.CurrentUserContext.runAs("mom", () ->
                assertThat(gateway.codingRequest("pc_info", Map.of(), Duration.ofSeconds(1))).containsEntry("pc", "mom"));
        assertThat(ownerPc.sentMessages()).isEmpty();
        // The owner's PC disconnecting does not touch the other user's bridge.
        ownerPc.close();
        gateway.detach(ownerPc);
        com.jarvis.common.auth.CurrentUserContext.runAs("mom", () -> assertThat(gateway.codingStatus()).isEqualTo("CONNECTED"));
    }

    @Test
    void aResponseFromAnotherUsersPcCannotCompleteTheRequest() throws Exception {
        WebSocketWindowsMcpBridgeGateway gateway = new WebSocketWindowsMcpBridgeGateway(objectMapper);
        TestWebSocketSession ownerPc = userSession("pc-owner", "owner");
        TestWebSocketSession otherPc = userSession("pc-other", "other");
        gateway.register(ownerPc, "instance-owner");
        gateway.register(otherPc, "instance-other");
        ownerPc.onSend(sentMessage -> {
            try {
                String requestId = objectMapper.readTree(((TextMessage) sentMessage).getPayload()).path("requestId").asText();
                // A forged answer from the other user's PC is ignored; the real one from the owner's PC wins.
                gateway.handleResponse(otherPc, objectMapper.readTree("{\"requestId\":\"" + requestId
                        + "\",\"success\":true,\"payload\":{\"content\":\"forged\"}}"));
                gateway.handleResponse(ownerPc, objectMapper.readTree("{\"requestId\":\"" + requestId
                        + "\",\"success\":true,\"payload\":{\"content\":\"real\"}}"));
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
        });
        java.util.concurrent.atomic.AtomicReference<Map<String, Object>> result = new java.util.concurrent.atomic.AtomicReference<>();
        com.jarvis.common.auth.CurrentUserContext.runAs("owner", () ->
                result.set(gateway.codingRequest("pc_read", Map.of(), Duration.ofSeconds(1))));
        assertThat(result.get()).containsEntry("content", "real");
    }

    private TestWebSocketSession userSession(String id, String userId) {
        TestWebSocketSession session = fakeOpenSession(id);
        session.getAttributes().put("jarvis.userId", userId);
        return session;
    }

    private void respondImmediately(WebSocketWindowsMcpBridgeGateway gateway, TestWebSocketSession session, String payloadTemplate) {
        session.onSend(sentMessage -> {
            TextMessage message = (TextMessage) sentMessage;
            try {
                String requestId = objectMapper.readTree(message.getPayload()).path("requestId").asText();
                gateway.handleResponse(objectMapper.readTree(String.format(payloadTemplate, requestId)));
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
        });
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
            TestWebSocketSession session,
            ScheduledExecutorService scheduler,
            long delayMs,
            boolean success
    ) throws Exception {
        session.onSend(sentMessage -> {
            TextMessage message = (TextMessage) sentMessage;
            try {
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
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
        });
    }

    private TestWebSocketSession fakeOpenSession() {
        return fakeOpenSession("session-1");
    }

    private TestWebSocketSession fakeOpenSession(String id) {
        return new TestWebSocketSession(id);
    }
}
