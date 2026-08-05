package com.jarvis.tools;

/**
 * Contract for executable Jarvis tools.
 */
public interface Tool extends JarvisTool {

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

    @Override
    default String getName() {
        return name();
    }

    @Override
    default String getDescription() {
        return description();
    }

    @Override
    default ToolResult execute(ToolRequest request) {
        ToolExecutionResult result = execute(new ToolExecutionRequest(request.arguments()));
        return new ToolResult(result.success(), result.output(), result.metadata());
    }
}
