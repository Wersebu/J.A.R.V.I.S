package com.jarvis.tools.runtime;

import java.util.List;

/**
 * Debug snapshot of one native tool-calling loop.
 *
 * @param requestId request identifier
 * @param conversationId conversation identifier
 * @param intent detected intent
 * @param steps runtime steps
 * @param finalStatus final status
 * @param errors errors
 */
public record ToolRuntimeSnapshot(
        String requestId,
        String conversationId,
        ToolIntent intent,
        List<ToolRuntimeStep> steps,
        String finalStatus,
        List<String> errors
) {

    /**
     * Creates an immutable snapshot.
     */
    public ToolRuntimeSnapshot {
        steps = steps == null ? List.of() : List.copyOf(steps);
        errors = errors == null ? List.of() : List.copyOf(errors);
    }
}
