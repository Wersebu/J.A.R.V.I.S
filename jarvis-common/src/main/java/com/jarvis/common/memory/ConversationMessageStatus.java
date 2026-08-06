package com.jarvis.common.memory;

/**
 * Persistence status of a conversation message.
 */
public enum ConversationMessageStatus {
    /** Message was finalized and may be used as conversation context. */
    FINAL,
    /** Message is still in progress. */
    DRAFT,
    /** Message failed or was superseded. */
    FAILED
}
