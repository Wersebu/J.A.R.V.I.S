package com.jarvis.tools.runtime;

import com.jarvis.tools.ToolResult;

import java.util.List;

/**
 * Result of a native tool-calling loop.
 *
 * @param handled whether the runtime handled the request
 * @param finalAnswer final user-facing answer
 * @param steps executed steps
 * @param results tool results
 */
public record ToolCallingResult(
        boolean handled,
        String finalAnswer,
        List<ToolRuntimeStep> steps,
        List<ToolResult> results
) {

    /**
     * Creates an immutable result.
     */
    public ToolCallingResult {
        steps = steps == null ? List.of() : List.copyOf(steps);
        results = results == null ? List.of() : List.copyOf(results);
    }
}
