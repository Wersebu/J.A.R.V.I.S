package com.jarvis.tools.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StdioMcpClientTest {

    @Test
    void sendsInitializedNotificationPassesEnvAnswersPingsAndSkipsBanner() {
        McpServerProperties properties = new McpServerProperties();
        properties.setCommand(ProcessHandle.current().info().command().orElse("java"));
        properties.setArgs(List.of("-cp", System.getProperty("java.class.path"), FakeStrictMcpServerMain.class.getName()));
        properties.setEnv(Map.of("FAKE_TOKEN", "secret-123"));
        properties.setInitializeTimeout(Duration.ofSeconds(20));
        properties.setListToolsTimeout(Duration.ofSeconds(10));
        StdioMcpClient client = new StdioMcpClient("fake", properties, new ObjectMapper(), "test");
        try {
            client.initialize();
            List<McpToolDescriptor> tools = client.listTools();
            assertThat(tools).extracting(McpToolDescriptor::name).containsExactly("whoami", "delete_repo");

            McpCallResult result = client.callTool("whoami", Map.of(), Duration.ofSeconds(10));
            assertThat(result.success()).isTrue();
            assertThat(result.content().get(0).text()).isEqualTo("token=secret-123 pong=true");
        } finally {
            client.close();
        }
    }

    @Test
    void includeAndExcludeToolPatterns() {
        McpServerProperties properties = new McpServerProperties();
        assertThat(properties.exposesTool("anything")).isTrue();
        properties.setIncludeTools(List.of("get_*", "list_*", "create_issue"));
        properties.setExcludeTools(List.of("*secret*"));
        assertThat(properties.exposesTool("get_file_contents")).isTrue();
        assertThat(properties.exposesTool("CREATE_ISSUE")).isTrue();
        assertThat(properties.exposesTool("delete_repository")).isFalse();
        assertThat(properties.exposesTool("get_secret_scanning")).isFalse();
    }
}
