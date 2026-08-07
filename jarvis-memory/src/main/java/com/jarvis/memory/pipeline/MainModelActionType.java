package com.jarvis.memory.pipeline;

/**
 * Supported structured decisions returned by the main conversational model.
 */
public enum MainModelActionType {
    /** The model can answer directly without external capabilities. */
    FINAL_ANSWER,
    /** The model needs an external capability and the tool runtime should start. */
    TOOL_REQUEST,
    /** The model needs more information from the user before acting. */
    CLARIFICATION
}
