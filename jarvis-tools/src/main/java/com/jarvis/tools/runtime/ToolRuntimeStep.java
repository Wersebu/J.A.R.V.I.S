package com.jarvis.tools.runtime;

import com.jarvis.tools.ToolResult;

/**
 * One native tool runtime step.
 *
 * @param stepNumber step number
 * @param action action selected by the model/runtime
 * @param tool tool name
 * @param operation operation name
 * @param status step status
 * @param result tool result
 */
public record ToolRuntimeStep(
        int stepNumber,
        String action,
        String tool,
        String operation,
        String status,
        ToolResult result
) {
}
