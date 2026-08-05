package com.jarvis.tools;

import java.util.List;
import java.util.Optional;

/**
 * Registry and lookup contract for available tools.
 */
public interface ToolManager {

    /**
     * Lists all registered tools.
     *
     * @return registered tools
     */
    List<JarvisTool> listTools();

    /**
     * Finds a registered tool by name.
     *
     * @param name stable tool name
     * @return matching tool, when present
     */
    Optional<JarvisTool> findTool(String name);

    /**
     * Executes a registered tool.
     *
     * @param request native tool request
     * @return tool result
     */
    ToolResult execute(ToolRequest request);
}
