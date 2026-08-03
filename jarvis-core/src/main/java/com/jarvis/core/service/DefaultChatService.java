package com.jarvis.core.service;

import com.jarvis.api.service.ChatService;
import com.jarvis.common.dto.ChatRequest;
import com.jarvis.common.dto.ChatResponse;
import com.jarvis.common.memory.ConversationMessage;
import com.jarvis.common.memory.MessageRole;
import com.jarvis.memory.ConversationMemoryService;
import com.jarvis.ollama.OllamaService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * Default chat orchestration service for version 0.1.
 */
@Service
public class DefaultChatService implements ChatService {

    private final OllamaService ollamaService;
    private final ConversationMemoryService memoryService;

    /**
     * Creates the default chat service.
     *
     * @param ollamaService Ollama communication service
     * @param memoryService conversation memory service
     */
    public DefaultChatService(OllamaService ollamaService, ConversationMemoryService memoryService) {
        this.ollamaService = ollamaService;
        this.memoryService = memoryService;
    }

    /**
     * Stores the user message, delegates generation to Ollama, and stores the response.
     *
     * @param request chat request
     * @return plain text chat response
     */
    @Override
    public ChatResponse chat(ChatRequest request) {
        String conversationId = normalizeConversationId(request.conversationId());
        memoryService.addMessage(conversationId, new ConversationMessage(MessageRole.USER, request.message(), Instant.now()));

        String response = ollamaService.generate(request.message());

        memoryService.addMessage(conversationId, new ConversationMessage(MessageRole.ASSISTANT, response, Instant.now()));
        return new ChatResponse(conversationId, response);
    }

    private String normalizeConversationId(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return conversationId;
    }
}
