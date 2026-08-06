package com.jarvis.common.memory;

/**
 * Type of a stored conversation message.
 */
public enum ConversationMessageType {
    /** User-facing chat turn. */
    CHAT,
    /** Tool call trace. */
    TOOL_CALL,
    /** Tool result trace. */
    TOOL_RESULT,
    /** Draft approval action. */
    DRAFT_APPROVAL,
    /** System event that is safe to persist. */
    SYSTEM_EVENT
}
