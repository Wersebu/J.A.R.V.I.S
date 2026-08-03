package com.jarvis.api.service;

import com.jarvis.common.dto.ChatRequest;
import com.jarvis.common.dto.ChatResponse;

/**
 * Coordinates a client message with the backend AI provider.
 */
public interface ChatService {

    /**
     * Processes a chat request and returns plain text output.
     *
     * @param request chat request
     * @return generated chat response
     */
    ChatResponse chat(ChatRequest request);
}
