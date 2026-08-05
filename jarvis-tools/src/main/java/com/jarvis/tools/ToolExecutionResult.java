package com.jarvis.tools;

import java.util.Map;

/**
 * Output returned by a tool execution.
 *
 * @param success whether execution succeeded
 * @param output plain text output
 * @param metadata structured result metadata
 */
public record ToolExecutionResult(boolean success, String output, Map<String, Object> metadata) {

    /**
     * Creates an immutable result.
     */
    public ToolExecutionResult {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
