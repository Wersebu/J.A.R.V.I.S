package com.jarvis.common.prompt;

import com.jarvis.common.dto.ChatRequest;

/**
 * Composes prompts sent to AI providers.
 */
public interface PromptBuilder {

    /**
     * Builds a provider prompt from a user chat request.
     *
     * @param request user chat request
     * @return prompt text
     */
    String buildPrompt(ChatRequest request);
}
