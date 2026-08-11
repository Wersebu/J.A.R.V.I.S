package com.jarvis.common.ai;

import java.util.Map;

/**
 * Provider-independent native tool call.
 *
 * @param id provider tool call identifier
 * @param name model-facing tool/function name
 * @param arguments structured arguments
 */
public record ModelToolCall(
        String id,
        String name,
        Map<String, Object> arguments
) {

    /**
     * Creates an immutable model tool call.
     */
    public ModelToolCall {
        id = id == null ? "" : id;
        name = name == null ? "" : name;
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }
}
