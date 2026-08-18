package com.jarvis.tools.mcp;

import com.jarvis.tools.DefaultToolManager;
import com.jarvis.tools.JarvisTool;
import com.jarvis.tools.ToolException;
import com.jarvis.tools.ToolRequest;
import com.jarvis.tools.ToolResult;
import com.jarvis.tools.schema.DefaultToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpDynamicToolSourceTest {

    @Test
    void returnsNoToolsWhenMcpIsDisabled() {
        McpProperties properties = new McpProperties();
        properties.setEnabled(false);
        McpDynamicToolSource source = new McpDynamicToolSource(properties, new FakeMcpServerManager());

        assertThat(source.tools()).isEmpty();
    }

    @Test
    void exposesDiscoveredMcpToolsAsJarvisTools() {
        McpDynamicToolSource source = enabledSource(new McpToolDescriptor(
                "roblox",
                "createScript",
                "Creates a Roblox script.",
                Map.of("properties", Map.of("name", Map.of("type", "string", "description", "Script name")), "required", List.of("name")),
                McpAccessLevel.EDIT
        ));

        assertThat(source.tools())
                .extracting(JarvisTool::getName)
                .containsExactly("mcp_roblox_createscript");
    }

    @Test
    void registersDynamicToolDefinitions() {
        McpDynamicToolSource source = enabledSource(new McpToolDescriptor(
                "studio",
                "list",
                "Lists items.",
                Map.of("properties", Map.of("path", Map.of("type", "string")), "required", List.of("path")),
                McpAccessLevel.READ
        ));

        DefaultToolRegistry registry = new DefaultToolRegistry(List.of(), List.of(source));

        assertThat(registry.definitions())
                .singleElement()
                .satisfies(definition -> {
                    assertThat(definition.name()).isEqualTo("mcp_studio_list");
                    assertThat(definition.operations()).singleElement()
                            .satisfies(operation -> assertThat(operation.arguments()).extracting("name").containsExactly("path"));
                });
    }

    @Test
    void executesDiscoveredMcpTool() {
        McpDynamicToolSource source = enabledSource(new McpToolDescriptor(
                "server",
                "echo",
                "Echoes input.",
                Map.of("properties", Map.of("text", Map.of("type", "string")), "required", List.of("text")),
                McpAccessLevel.READ
        ));
        DefaultToolManager manager = new DefaultToolManager(List.of(), List.of(source));

        ToolResult result = manager.execute(new ToolRequest(
                "mcp_server_echo",
                "CALL",
                "conversation-1",
                "request-1",
                "test",
                "",
                Map.of("text", "hello")
        ));

        assertThat(result.success()).isTrue();
        assertThat(result.message()).contains("MCP OK");
        assertThat(result.data()).containsEntry("mcpServer", "server");
    }

    @Test
    void rejectsDynamicToolNameCollisions() {
        McpDynamicToolSource source = enabledSource(new McpToolDescriptor(
                "native",
                "tool",
                "Colliding tool.",
                Map.of(),
                McpAccessLevel.READ
        ));
        DefaultToolManager manager = new DefaultToolManager(List.of(new StaticTool()), List.of(source));

        assertThatThrownBy(manager::listTools)
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("Duplicate dynamic tool");
    }

    private McpDynamicToolSource enabledSource(McpToolDescriptor descriptor) {
        McpProperties properties = new McpProperties();
        properties.setEnabled(true);
        return new McpDynamicToolSource(properties, new FakeMcpServerManager(descriptor));
    }

    private static class FakeMcpServerManager implements McpServerManager {

        private final List<McpToolDescriptor> descriptors;

        FakeMcpServerManager(McpToolDescriptor... descriptors) {
            this.descriptors = List.of(descriptors);
        }

        @Override
        public void connect(String serverId) {
        }

        @Override
        public void disconnect(String serverId) {
        }

        @Override
        public List<McpServerStatus> statuses() {
            return List.of();
        }

        @Override
        public List<McpToolDescriptor> discoverTools() {
            return descriptors;
        }

        @Override
        public McpCallResult call(McpToolDescriptor descriptor, ToolRequest request) {
            return new McpCallResult(true, List.of(new McpContentItem("text", "MCP OK", null, null, Map.of())), Map.of(), "", "");
        }
    }

    private static class StaticTool implements JarvisTool {

        @Override
        public String getName() {
            return "mcp_native_tool";
        }

        @Override
        public String getDescription() {
            return "Static collision tool.";
        }

        @Override
        public ToolResult execute(ToolRequest request) {
            return new ToolResult(true, getName(), request.operation(), request.requestId(), request.conversationId(),
                    false, List.of(), "ok", Map.of(), "", "", false, "");
        }
    }
}
