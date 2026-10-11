package com.jarvis.core.pc;

import com.jarvis.api.service.WindowsCodingBridgeGateway;
import com.jarvis.tools.ToolRequest;
import com.jarvis.tools.ToolResult;
import com.jarvis.tools.mcp.McpException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PcToolTest {

    private final List<String> operations = new ArrayList<>();
    private final List<Map<String, Object>> payloads = new ArrayList<>();
    private final List<Duration> timeouts = new ArrayList<>();

    private final WindowsCodingBridgeGateway gateway = new WindowsCodingBridgeGateway() {
        @Override
        public String codingStatus() {
            return "CONNECTED";
        }

        @Override
        public Map<String, Object> codingRequest(String operation, Map<String, Object> payload, Duration timeout) {
            operations.add(operation);
            payloads.add(payload);
            timeouts.add(timeout);
            return Map.of("content", "hello", "path", "C:\\Users\\D\\a.txt");
        }
    };

    @Test
    void spotifyReadMessagesPreserveConnectionAndDeviceFactsWithoutSideEffects() {
        List<Map<String, Object>> replies = List.of(
                Map.of("connected", true, "configured", true, "authorizationPending", false),
                Map.of("connected", false, "configured", true, "authorizationPending", false, "lastError", "Token expired"),
                Map.of("devices", List.of()),
                Map.of("devices", List.of(Map.of("name", "Desktop"))));
        WindowsCodingBridgeGateway spotifyGateway = new WindowsCodingBridgeGateway() {
            public String codingStatus() { return "CONNECTED"; }
            public Map<String, Object> codingRequest(String operation, Map<String, Object> payload, Duration timeout) {
                operations.add(operation);
                payloads.add(payload);
                return replies.get(payloads.size() - 1);
            }
        };
        PcTool tool = new PcTool(spotifyGateway, true);
        ToolResult connected = tool.execute(request("SPOTIFY", Map.of("action", "status")));
        ToolResult disconnected = tool.execute(request("SPOTIFY", Map.of("action", "status")));
        ToolResult empty = tool.execute(request("SPOTIFY", Map.of("action", "devices")));
        ToolResult available = tool.execute(request("SPOTIFY", Map.of("action", "devices")));
        assertThat(connected.message()).contains("connected=true");
        assertThat(disconnected.message()).contains("connected=false", "NOT connected", "Token expired", "only if");
        assertThat(empty.message()).contains("0 devices", "valid result");
        assertThat(available.message()).contains("1 device(s)");
        for (ToolResult result : List.of(connected, disconnected, empty, available)) {
            assertThat(result.success()).isTrue();
            assertThat(result.changed()).isFalse();
        }
        assertThat(payloads).extracting(payload -> payload.get("action")).containsExactly("status", "status", "devices", "devices");
        assertThat(operations).containsOnly("pc_spotify");
        assertThat(disconnected.data()).containsEntry("connected", false);
        assertThat(empty.data()).containsEntry("devices", List.of());
    }

    @Test
    void spotifyIsDiscoverableAndForwardsApiArguments() {
        PcTool tool = new PcTool(gateway, true);
        assertThat(tool.definition().operations()).anySatisfy(op ->
                assertThat(op.name()).isEqualTo("SPOTIFY"));
        ToolResult result = tool.execute(request("SPOTIFY", Map.of("action", "search", "query", "Daft Punk")));
        assertThat(result.success()).isTrue();
        assertThat(operations).containsExactly("pc_spotify");
        assertThat(payloads.get(0)).containsEntry("session", "c").containsEntry("query", "Daft Punk");
        assertThat(timeouts.get(0)).isEqualTo(Duration.ofSeconds(120));
    }

    @Test
    void spotifyMarksMutationsButNotLibraryReadsAsChanged() {
        PcTool tool = new PcTool(gateway, true);
        assertThat(tool.execute(request("SPOTIFY", Map.of("action", "liked"))).changed()).isFalse();
        assertThat(tool.execute(request("SPOTIFY", Map.of("action", "play", "target", "spotify:track:7fODjB7BrQTGqh0hogW6XD", "deviceId", "pc"))).changed()).isTrue();
        assertThat(payloads.get(1)).containsEntry("deviceId", "pc").containsEntry("target", "spotify:track:7fODjB7BrQTGqh0hogW6XD");
    }

    @Test
    void forwardsOperationsToTheWindowsBridgeWithoutInternalArguments() {
        PcTool tool = new PcTool(gateway, true);
        ToolResult result = tool.execute(request("READ", Map.of("path", "C:\\Users\\D\\a.txt", "_activeCodingWorkspaceId", "w")));

        assertThat(result.success()).isTrue();
        assertThat(result.data()).containsEntry("content", "hello");
        assertThat(operations).containsExactly("pc_read");
        assertThat(payloads.get(0)).containsOnlyKeys("path", "session");
    }

    @Test
    void shellTimeoutIsClampedAndCoreWaitsLongerThanWindows() {
        PcTool tool = new PcTool(gateway, true);
        tool.execute(request("SHELL", Map.of("command", "mvn test", "timeoutSeconds", 5000)));

        assertThat(payloads.get(0)).containsEntry("timeoutSeconds", 900L);
        assertThat(timeouts.get(0)).isEqualTo(Duration.ofSeconds(1230));
    }

    @Test
    void shellUsesTheConversationAsTerminalSessionAndWaitGetsLongerCoreTimeout() {
        PcTool tool = new PcTool(gateway, true);
        tool.execute(request("SHELL", Map.of("command", "cd app")));
        tool.execute(request("SHELL_WAIT", Map.of("processId", "p1", "waitSeconds", 9999)));

        assertThat(payloads.get(0)).containsEntry("session", "c");
        assertThat(payloads.get(1)).containsEntry("waitSeconds", 600L);
        assertThat(timeouts.get(1)).isEqualTo(Duration.ofSeconds(630));
        assertThat(operations).containsExactly("pc_shell", "pc_shell_wait");
    }

    @Test
    void deleteIsForwardedWithTimeForTheUsersConfirmationOnThePc() {
        PcTool tool = new PcTool(gateway, true);
        ToolResult result = tool.execute(request("DELETE", Map.of("path", "C:\\x")));

        assertThat(result.success()).isTrue();
        assertThat(operations).containsExactly("pc_delete");
        assertThat(timeouts.get(0)).isEqualTo(Duration.ofSeconds(330));
    }

    @Test
    void bridgeFailureBecomesAToolFailureWithAHint() {
        PcTool tool = new PcTool(new WindowsCodingBridgeGateway() {
            @Override
            public String codingStatus() {
                return "DISCONNECTED";
            }

            @Override
            public Map<String, Object> codingRequest(String operation, Map<String, Object> payload, Duration timeout) {
                throw new McpException("Windows MCP bridge is not connected.");
            }
        }, true);

        ToolResult result = tool.execute(request("INFO", Map.of()));
        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("Windows app must be running");
    }

    @Test
    void disabledToolExposesNoOperations() {
        assertThat(new PcTool(gateway, false).definition().operations()).isEmpty();
    }

    private ToolRequest request(String operation, Map<String, Object> arguments) {
        return new ToolRequest("pc", operation, "c", "r", "", "", arguments);
    }
}
