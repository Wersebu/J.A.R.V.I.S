package com.jarvis.common.memory;

import java.time.Instant;

/**
 * Single message stored in a conversation history.
 *
 * @param role message author role
 * @param content message content
 * @param createdAt creation timestamp
 */
public record ConversationMessage(MessageRole role, String content, Instant createdAt) {
}
