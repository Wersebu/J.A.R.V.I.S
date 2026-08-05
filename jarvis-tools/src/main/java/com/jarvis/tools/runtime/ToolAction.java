package com.jarvis.tools.runtime;

import java.util.Map;

/**
 * Parsed model action in the native tool loop.
 *
 * @param action action type
 * @param tool tool name
 * @param operation operation name
 * @param arguments structured arguments
 * @param reason action reason
 * @param answer final answer
 */
public record ToolAction(
        String action,
        String tool,
        String operation,
        Map<String, Object> arguments,
        String reason,
        String answer
) {

    /**
     * Creates an immutable action.
     */
    public ToolAction {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }
}
