package com.jarvis.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Spring-backed registry for native J.A.R.V.I.S. tools.
 */
@Service
public class DefaultToolManager implements ToolManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultToolManager.class);

    private final Map<String, JarvisTool> tools;

    /**
     * Creates the manager and registers all discovered tools.
     *
     * @param discoveredTools Spring-discovered tools
     */
    public DefaultToolManager(List<JarvisTool> discoveredTools) {
        Map<String, JarvisTool> registered = new LinkedHashMap<>();
        for (JarvisTool tool : discoveredTools) {
            JarvisTool previous = registered.putIfAbsent(normalize(tool.getName()), tool);
            if (previous != null) {
                throw new ToolException("Duplicate tool registered: " + tool.getName());
            }
            LOGGER.info("[TOOL] Registered tool name={}", tool.getName());
        }
        this.tools = Map.copyOf(registered);
    }

    /**
     * Model-facing native tool names are always lowercased (see {@code NativeToolSchemaMapper}),
     * so lookups must be case-insensitive regardless of how a tool's own {@code getName()} is cased.
     */
    private static String normalize(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }

    @Override
    public List<JarvisTool> listTools() {
        return List.copyOf(tools.values());
    }

    @Override
    public Optional<JarvisTool> findTool(String name) {
        return Optional.ofNullable(tools.get(normalize(name)));
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        if (request == null || request.toolName() == null || request.toolName().isBlank()) {
            throw new ToolException("Tool name is required");
        }
        JarvisTool tool = tools.get(normalize(request.toolName()));
        if (tool == null) {
            throw new ToolException("Tool not registered: " + request.toolName());
        }
        LOGGER.info("[TOOL] Executing tool={} operation={}", request.toolName(), request.operation());
        ToolResult result = tool.execute(request);
        return new ToolResult(
                result.success(),
                result.tool().isBlank() ? request.toolName() : result.tool(),
                request.operation(),
                result.requestId().isBlank() ? request.requestId() : result.requestId(),
                result.conversationId().isBlank() ? request.conversationId() : result.conversationId(),
                result.changed(),
                result.targetNodeIds(),
                result.message(),
                result.data(),
                result.errorCode(),
                result.errorMessage(),
                result.requiresApproval(),
                result.draftId()
        );
    }
}
