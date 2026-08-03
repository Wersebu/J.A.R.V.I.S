package com.jarvis.common.dto;

/**
 * Request sent by a client to continue a conversation.
 *
 * @param conversationId stable conversation identifier
 * @param message user message text
 */
public record ChatRequest(String conversationId, String message) {
}
