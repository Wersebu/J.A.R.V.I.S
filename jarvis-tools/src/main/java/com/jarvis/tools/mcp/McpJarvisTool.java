package com.jarvis.tools.mcp;

import com.jarvis.tools.JarvisTool;
import com.jarvis.tools.ToolRequest;
import com.jarvis.tools.ToolResult;
import com.jarvis.tools.schema.ToolDefinition;
import com.jarvis.tools.schema.ToolOperationDefinition;
import com.jarvis.tools.schema.ToolSchemaProvider;

import java.util.List;
import java.util.Map;

/**
 * Jarvis tool adapter for one MCP-discovered tool.
 */
public class McpJarvisTool implements JarvisTool, ToolSchemaProvider {

    private static final String OPERATION_CALL = "CALL";

    private final McpToolDescriptor descriptor;
    private final McpServerManager manager;
    private final McpSchemaMapper schemaMapper;

    /**
     * Creates the MCP tool adapter.
     *
     * @param descriptor MCP tool descriptor
     * @param manager MCP server manager
     * @param schemaMapper schema mapper
     */
    public McpJarvisTool(McpToolDescriptor descriptor, McpServerManager manager, McpSchemaMapper schemaMapper) {
        this.descriptor = descriptor;
        this.manager = manager;
        this.schemaMapper = schemaMapper;
    }

    @Override
    public String getName() {
        return descriptor.jarvisToolName();
    }

    /**
     * Returns this tool's MCP descriptor - used by diagnostic tracing to show both the model-facing
     * name ({@link McpToolDescriptor#jarvisToolName()}) and the real MCP tool/server names a call
     * resolves to, without the caller needing to re-derive them from the concatenated name.
     *
     * @return the MCP tool descriptor
     */
    public McpToolDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public String getDescription() {
        return "MCP tool from server '" + descriptor.serverId() + "': " + descriptor.description();
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(getName(), getDescription(), List.of(new ToolOperationDefinition(
                OPERATION_CALL,
                "Call MCP tool '" + descriptor.name() + "' on server '" + descriptor.serverId() + "'.",
                schemaMapper.arguments(descriptor.inputSchema()),
                descriptor.accessLevel() != McpAccessLevel.READ,
                descriptor.accessLevel().toToolSafetyLevel()
        )));
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        McpCallResult result = manager.call(descriptor, request);
        return new ToolResult(
                result.success(),
                getName(),
                OPERATION_CALL,
                request.requestId(),
                request.conversationId(),
                false,
                List.of("mcp:" + descriptor.serverId() + ":" + descriptor.name()),
                result.success() ? message(result) : result.errorMessage(),
                Map.of(
                        "mcpServer", descriptor.serverId(),
                        "mcpTool", descriptor.name(),
                        "content", result.content(),
                        "structuredContent", result.structuredContent()
                ),
                result.errorCode(),
                result.errorMessage(),
                false,
                ""
        );
    }

    private String message(McpCallResult result) {
        String text = result.text();
        if (!text.isBlank()) {
            return text;
        }
        if (!result.structuredContent().isEmpty()) {
            return "MCP tool returned structured content.";
        }
        return "MCP tool completed.";
    }
}
