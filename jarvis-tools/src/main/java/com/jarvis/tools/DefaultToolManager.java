package com.jarvis.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
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
            JarvisTool previous = registered.putIfAbsent(tool.getName(), tool);
            if (previous != null) {
                throw new ToolException("Duplicate tool registered: " + tool.getName());
            }
            LOGGER.info("[TOOL] Registered tool name={}", tool.getName());
        }
        this.tools = Map.copyOf(registered);
    }

    @Override
    public List<JarvisTool> listTools() {
        return List.copyOf(tools.values());
    }

    @Override
    public Optional<JarvisTool> findTool(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        if (request == null || request.toolName() == null || request.toolName().isBlank()) {
            throw new ToolException("Tool name is required");
        }
        JarvisTool tool = tools.get(request.toolName());
        if (tool == null) {
            throw new ToolException("Tool not registered: " + request.toolName());
        }
        LOGGER.info("[TOOL] Executing tool={} operation={}", request.toolName(), request.operation());
        return tool.execute(request);
    }
}
