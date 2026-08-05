package com.jarvis.tools;

import java.util.Map;

/**
 * Structured native tool result.
 *
 * @param success whether execution succeeded
 * @param output concise model-readable output
 * @param metadata structured diagnostics and data
 */
public record ToolResult(boolean success, String output, Map<String, Object> metadata) {

    /**
     * Creates an immutable result.
     */
    public ToolResult {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
