package com.jarvis.tools.runtime;

/**
 * Executes native model tool-calling loops.
 */
public interface ToolCallingRuntime {

    /**
     * Runs a tool loop when the request needs tools.
     *
     * @param request tool loop request
     * @return tool loop result
     */
    ToolCallingResult execute(ToolCallingRequest request);
}
