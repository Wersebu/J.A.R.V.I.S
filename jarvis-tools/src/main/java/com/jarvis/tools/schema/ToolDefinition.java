package com.jarvis.tools.schema;

import java.util.List;

/**
 * Model-facing native tool definition.
 *
 * @param name tool name
 * @param description tool description
 * @param operations supported operations
 */
public record ToolDefinition(String name, String description, List<ToolOperationDefinition> operations) {

    /**
     * Creates an immutable tool definition.
     */
    public ToolDefinition {
        operations = operations == null ? List.of() : List.copyOf(operations);
    }
}
