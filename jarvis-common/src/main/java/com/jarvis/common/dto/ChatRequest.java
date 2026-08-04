package com.jarvis.common.dto;

import java.time.Instant;

/**
 * Request sent by a client to continue a conversation.
 *
 * @param conversationId stable conversation identifier
 * @param message user message text
 * @param clientRequestTimestamp timestamp captured by the client when Send was pressed
 */
public record ChatRequest(String conversationId, String message, Instant clientRequestTimestamp) {

    /**
     * Creates a chat request without a client-side timestamp.
     *
     * @param conversationId stable conversation identifier
     * @param message user message text
     */
    public ChatRequest(String conversationId, String message) {
        this(conversationId, message, null);
    }
}
