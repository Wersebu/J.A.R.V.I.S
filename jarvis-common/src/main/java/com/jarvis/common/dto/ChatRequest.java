package com.jarvis.common.dto;

import com.jarvis.common.knowledge.KnowledgeMode;

import java.time.Instant;

/**
 * Request sent by a client to continue a conversation.
 *
 * @param conversationId stable conversation identifier
 * @param message user message text
 * @param clientRequestTimestamp timestamp captured by the client when Send was pressed
 * @param knowledgeMode requested knowledge strategy
 */
public record ChatRequest(String conversationId, String message, Instant clientRequestTimestamp, KnowledgeMode knowledgeMode) {

    /**
     * Normalizes optional values.
     */
    public ChatRequest {
        knowledgeMode = knowledgeMode == null ? KnowledgeMode.AUTO : knowledgeMode;
    }

    /**
     * Creates a chat request with a client-side timestamp.
     *
     * @param conversationId stable conversation identifier
     * @param message user message text
     * @param clientRequestTimestamp timestamp captured by the client when Send was pressed
     */
    public ChatRequest(String conversationId, String message, Instant clientRequestTimestamp) {
        this(conversationId, message, clientRequestTimestamp, KnowledgeMode.AUTO);
    }

    /**
     * Creates a chat request without a client-side timestamp.
     *
     * @param conversationId stable conversation identifier
     * @param message user message text
     */
    public ChatRequest(String conversationId, String message) {
        this(conversationId, message, null, KnowledgeMode.AUTO);
    }
}
