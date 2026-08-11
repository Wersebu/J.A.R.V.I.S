package com.jarvis.common.ai;

import java.util.Map;

/**
 * Provider-independent native function/tool definition.
 *
 * @param name function name visible to the model
 * @param description function description
 * @param parameters JSON-schema-like parameter definition
 */
public record NativeToolDefinition(
        String name,
        String description,
        Map<String, Object> parameters
) {

    /**
     * Creates an immutable native tool definition.
     */
    public NativeToolDefinition {
        name = name == null ? "" : name;
        description = description == null ? "" : description;
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    }
}
