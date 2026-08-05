package com.jarvis.tools.schema;

/**
 * Provides a safe model-facing schema for a native tool.
 */
public interface ToolSchemaProvider {

    /**
     * Returns the safe tool definition.
     *
     * @return tool definition
     */
    ToolDefinition definition();
}
