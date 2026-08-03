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
    List<Tool> listTools();

    /**
     * Finds a registered tool by name.
     *
     * @param name stable tool name
     * @return matching tool, when present
     */
    Optional<Tool> findTool(String name);
}
