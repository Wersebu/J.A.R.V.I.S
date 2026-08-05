package com.jarvis.tools;

import java.util.Map;

/**
 * Input passed to a tool execution.
 *
 * @param arguments structured tool arguments
 */
public record ToolExecutionRequest(Map<String, Object> arguments) {

    /**
     * Creates an immutable request.
     */
    public ToolExecutionRequest {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }
}
