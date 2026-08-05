package com.jarvis.tools.schema;

import java.util.List;

/**
 * Model-facing tool operation definition.
 *
 * @param name operation name
 * @param description operation description
 * @param arguments argument definitions
 * @param write whether the operation changes state
 * @param safetyLevel safety level
 */
public record ToolOperationDefinition(
        String name,
        String description,
        List<ToolArgumentDefinition> arguments,
        boolean write,
        ToolSafetyLevel safetyLevel
) {

    /**
     * Creates an immutable operation definition.
     */
    public ToolOperationDefinition {
        arguments = arguments == null ? List.of() : List.copyOf(arguments);
    }
}
