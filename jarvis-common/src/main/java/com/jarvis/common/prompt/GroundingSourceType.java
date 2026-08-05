package com.jarvis.common.prompt;

/**
 * Type of source that can ground a generated response.
 */
public enum GroundingSourceType {
    /** Retrieved cognitive memory. */
    MEMORY,
    /** Retrieved knowledge document. */
    KNOWLEDGE,
    /** Current conversation history. */
    CONVERSATION,
    /** Tool execution result. */
    TOOL,
    /** Explicit content from the current user message. */
    USER_MESSAGE
}
