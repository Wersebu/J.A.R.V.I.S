package com.jarvis.common.ai;

import com.jarvis.common.dto.ChatRequest;
import com.jarvis.common.dto.ChatResponse;

/**
 * Provider-independent contract for AI chat generation.
 */
public interface AIProvider {

    /**
     * Sends a chat request to the configured AI provider.
     *
     * @param request provider chat request
     * @return provider chat response
     */
    ChatResponse chat(ChatRequest request);
}
