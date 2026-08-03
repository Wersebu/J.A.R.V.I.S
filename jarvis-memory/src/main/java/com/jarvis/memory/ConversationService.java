package com.jarvis.memory;

import com.jarvis.common.dto.ChatRequest;
import com.jarvis.common.dto.ChatResponse;

/**
 * Orchestrates conversation state and provider calls.
 */
public interface ConversationService {

    /**
     * Processes a user chat request within a conversation.
     *
     * @param request user chat request
     * @return generated chat response
     */
    ChatResponse chat(ChatRequest request);
}
