package com.jarvis.tools.runtime;

import com.jarvis.tools.ToolResult;

import java.util.Map;

/**
 * One native tool runtime step.
 *
 * @param stepNumber step number
 * @param action action selected by the model/runtime
 * @param tool tool name
 * @param operation operation name
 * @param status step status
 * @param result tool result
 * @param arguments call arguments (e.g. {@code action} of pc__spotify) - needed to classify
 *                  generic operations whose real meaning lives in an argument
 */
public record ToolRuntimeStep(
        int stepNumber,
        String action,
        String tool,
        String operation,
        String status,
        ToolResult result,
        Map<String, Object> arguments
) {

    /**
     * Creates an immutable step.
     */
    public ToolRuntimeStep {
        arguments = arguments == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(arguments));
    }

    /**
     * Creates a step without recorded arguments.
     */
    public ToolRuntimeStep(int stepNumber, String action, String tool, String operation, String status, ToolResult result) {
        this(stepNumber, action, tool, operation, status, result, Map.of());
    }
}
