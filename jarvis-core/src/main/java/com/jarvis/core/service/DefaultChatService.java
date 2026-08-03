package com.jarvis.core.service;

import com.jarvis.api.service.ChatService;
import com.jarvis.common.dto.ChatRequest;
import com.jarvis.common.dto.ChatResponse;
import com.jarvis.memory.ConversationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Default chat entry service for version 0.2.
 */
@Service
public class DefaultChatService implements ChatService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultChatService.class);

    private final ConversationService conversationService;

    /**
     * Creates the default chat service.
     *
     * @param conversationService conversation service
     */
    public DefaultChatService(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    /**
     * Delegates a user request into the conversation pipeline.
     *
     * @param request chat request
     * @return plain text chat response
     */
    @Override
    public ChatResponse chat(ChatRequest request) {
        LOGGER.info("[JARVIS] Incoming request");
        return conversationService.chat(request);
    }
}
