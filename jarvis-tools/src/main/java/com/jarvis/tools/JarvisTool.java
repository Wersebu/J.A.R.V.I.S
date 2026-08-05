package com.jarvis.tools;

/**
 * Native executable tool available to the J.A.R.V.I.S. model runtime.
 */
public interface JarvisTool {

    /**
     * Returns the stable tool name used by the model.
     *
     * @return tool name
     */
    String getName();

    /**
     * Returns a concise tool description.
     *
     * @return tool description
     */
    String getDescription();

    /**
     * Executes one tool request.
     *
     * @param request tool request
     * @return tool result
     */
    ToolResult execute(ToolRequest request);
}
