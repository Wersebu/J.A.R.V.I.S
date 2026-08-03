package com.jarvis.tools;

/**
 * Contract for executable Jarvis tools.
 */
public interface Tool {

    /**
     * Returns the stable tool name.
     *
     * @return tool name
     */
    String name();

    /**
     * Returns a human-readable tool description.
     *
     * @return tool description
     */
    String description();

    /**
     * Executes the tool.
     *
     * @param request execution request
     * @return execution result
     */
    ToolExecutionResult execute(ToolExecutionRequest request);
}
