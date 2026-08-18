package com.jarvis.tools.runtime;

/**
 * Thrown when a native model tool call supplies an argument whose runtime shape does not match
 * the tool's declared JSON Schema (e.g. a plain string where an array or object was declared).
 * Kept distinct from a generic malformed-call {@link com.jarvis.tools.ToolException} so the loop
 * can surface a precise {@code INVALID_TOOL_ARGUMENT} error code back to the model instead of the
 * catch-all {@code INVALID_TOOL_CALL}.
 */
public class InvalidToolArgumentException extends RuntimeException {

    /**
     * Creates the exception.
     *
     * @param message precise description of the argument/type mismatch
     */
    public InvalidToolArgumentException(String message) {
        super(message);
    }
}
