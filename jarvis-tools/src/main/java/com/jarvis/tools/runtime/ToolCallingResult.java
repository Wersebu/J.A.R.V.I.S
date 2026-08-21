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
 * @param terminationInfo structured account of why/how the loop ended
 */
public record ToolCallingResult(
        boolean handled,
        String finalAnswer,
        List<ToolRuntimeStep> steps,
        List<ToolResult> results,
        ToolLoopTerminationInfo terminationInfo
) {

    /**
     * Creates an immutable result.
     */
    public ToolCallingResult {
        steps = steps == null ? List.of() : List.copyOf(steps);
        results = results == null ? List.of() : List.copyOf(results);
        terminationInfo = terminationInfo == null ? ToolLoopTerminationInfo.unknown() : terminationInfo;
    }

    /**
     * Backward-compatible constructor for callers that do not (yet) compute a termination reason -
     * carries {@link ToolLoopTerminationInfo#unknown()} instead.
     *
     * @param handled whether the runtime handled the request
     * @param finalAnswer final user-facing answer
     * @param steps executed steps
     * @param results tool results
     */
    public ToolCallingResult(boolean handled, String finalAnswer, List<ToolRuntimeStep> steps, List<ToolResult> results) {
        this(handled, finalAnswer, steps, results, ToolLoopTerminationInfo.unknown());
    }
}
