package com.jarvis.core.pc;

import com.jarvis.api.service.WindowsCodingBridgeGateway;
import com.jarvis.tools.ToolRequest;
import com.jarvis.tools.ToolResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BrowserToolTest {

    private final List<String> operations = new ArrayList<>();
    private final List<Map<String, Object>> payloads = new ArrayList<>();
    private final List<Duration> timeouts = new ArrayList<>();
    private Map<String, Object> answer = Map.of("url", "https://example.pl", "title", "Example", "elements", "[1] link: Opinie");

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
            return answer;
        }
    };

    @Test
    void actionsGoToTheConversationsBrowserTabOnThePc() {
        BrowserTool tool = new BrowserTool(gateway, true);

        ToolResult opened = tool.execute(request("OPEN", Map.of("url", "https://example.pl", "_workingDirectory", "D:\\Site")));
        tool.execute(request("CLICK", Map.of("ref", "1")));

        assertThat(opened.success()).isTrue();
        assertThat(opened.data()).containsEntry("elements", "[1] link: Opinie");
        assertThat(operations).containsExactly("web_open", "web_click");
        assertThat(payloads.get(0)).containsEntry("session", "conv").containsEntry("baseDir", "D:\\Site")
                .doesNotContainKey("_workingDirectory");
        assertThat(timeouts.get(1)).isEqualTo(Duration.ofSeconds(330)); // a "buy" click may wait for the user
    }

    @Test
    void screenshotBytesAreMovedOutOfTheTextForTheVisionModel() {
        answer = Map.of("url", "https://example.pl", "imageBase64", "iVBORw0KGgo");
        ToolResult shot = new BrowserTool(gateway, true).execute(request("SCREENSHOT", Map.of()));

        assertThat(shot.data()).containsEntry("_imageBase64", "iVBORw0KGgo").doesNotContainKey("imageBase64");
    }

    @Test
    void offersEveryOperationAndFailsWithAHintWithoutTheWindowsApp() {
        BrowserTool tool = new BrowserTool(new WindowsCodingBridgeGateway() {
            @Override
            public String codingStatus() {
                return "DISCONNECTED";
            }

            @Override
            public Map<String, Object> codingRequest(String operation, Map<String, Object> payload, Duration timeout) {
                throw new IllegalStateException("Windows Bridge not connected");
            }
        }, true);

        assertThat(tool.definition().operations()).extracting(operation -> operation.name())
                .contains("OPEN", "CLICK", "TYPE", "SCROLL", "SCREENSHOT", "BACK");
        ToolResult result = tool.execute(request("OPEN", Map.of("url", "x.pl")));
        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("Jarvis Windows app must be running");
    }

    private static ToolRequest request(String operation, Map<String, Object> arguments) {
        return new ToolRequest("browser", operation, "conv", "req", "", "", arguments);
    }
}
