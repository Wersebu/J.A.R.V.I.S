package com.jarvis.common.prompt;

import com.jarvis.common.context.KnowledgeContext;
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
    default String buildPrompt(ChatRequest request) {
        return buildPrompt(request, KnowledgeContext.empty());
    }

    /**
     * Builds a provider prompt from a user chat request and knowledge context.
     *
     * @param request user chat request
     * @param knowledgeContext knowledge context
     * @return prompt text
     */
    String buildPrompt(ChatRequest request, KnowledgeContext knowledgeContext);

    /**
     * Builds a debug view of the provider prompt.
     *
     * @param request user chat request
     * @param knowledgeContext knowledge context
     * @return prompt debug result
     */
    PromptDebugResult buildDebugPrompt(ChatRequest request, KnowledgeContext knowledgeContext);
}
