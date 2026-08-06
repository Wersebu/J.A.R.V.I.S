package com.jarvis.common.memory;

/**
 * Supported roles for conversation history entries.
 */
public enum MessageRole {
    /**
     * System-authored conversation note.
     */
    SYSTEM,

    /**
     * Message created by the end user.
     */
    USER,

    /**
     * Message created by Jarvis.
     */
    ASSISTANT,

    /**
     * Tool call message.
     */
    TOOL,

    /**
     * Tool result message.
     */
    TOOL_RESULT
}
