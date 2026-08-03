package com.jarvis.memory;

import com.jarvis.common.ai.AIProvider;
import com.jarvis.common.dto.ChatRequest;
import com.jarvis.common.dto.ChatResponse;
import com.jarvis.common.memory.ConversationMessage;
import com.jarvis.common.memory.MessageRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * In-memory conversation orchestration service for version 0.2.
 */
@Service
public class InMemoryConversationService implements ConversationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(InMemoryConversationService.class);

    private final ConversationMemoryService memoryService;
    private final AIProvider aiProvider;

    /**
     * Creates the in-memory conversation service.
     *
     * @param memoryService conversation memory store
     * @param aiProvider configured AI provider
     */
    public InMemoryConversationService(
            ConversationMemoryService memoryService,
            AIProvider aiProvider
    ) {
        this.memoryService = memoryService;
        this.aiProvider = aiProvider;
    }

    /**
     * Stores the user message, calls the AI provider, and stores the response.
     *
     * @param request user chat request
     * @return generated chat response
     */
    @Override
    public ChatResponse chat(ChatRequest request) {
        String conversationId = normalizeConversationId(request.conversationId());
        LOGGER.info("[JARVIS] Conversation id: {}", conversationId);

        memoryService.addMessage(conversationId, new ConversationMessage(MessageRole.USER, request.message(), Instant.now()));

        ChatResponse response = aiProvider.chat(new ChatRequest(conversationId, request.message()));

        memoryService.addMessage(conversationId, new ConversationMessage(MessageRole.ASSISTANT, response.response(), Instant.now()));
        return response;
    }

    private String normalizeConversationId(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return conversationId;
    }
}
