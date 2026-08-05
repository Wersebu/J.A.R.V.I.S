package com.jarvis.tools.schema;

import java.util.List;

/**
 * Read-only registry of model-facing tool definitions.
 */
public interface ToolRegistry {

    /**
     * Lists safe tool definitions.
     *
     * @return tool definitions
     */
    List<ToolDefinition> definitions();

    /**
     * Builds a concise prompt section describing available tools.
     *
     * @return model prompt section
     */
    String promptSection();
}
