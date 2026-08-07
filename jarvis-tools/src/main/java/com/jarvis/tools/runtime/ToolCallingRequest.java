package com.jarvis.tools.runtime;

import com.jarvis.common.ai.Brain;
import com.jarvis.common.knowledge.KnowledgeMode;

/**
 * Input for a native tool-calling loop.
 *
 * @param requestId request identifier
 * @param conversationId conversation identifier
 * @param userMessage latest user message
 * @param goal external-capability goal selected by the main model
 * @param reason main model decision reason summary
 * @param basePrompt existing prompt context
 * @param brain selected brain
 * @param knowledgeMode effective knowledge mode
 */
public record ToolCallingRequest(
        String requestId,
        String conversationId,
        String userMessage,
        String goal,
        String reason,
        String basePrompt,
        Brain brain,
        KnowledgeMode knowledgeMode
) {

    /**
     * Compatibility constructor.
     *
     * @param requestId request identifier
     * @param conversationId conversation identifier
     * @param userMessage latest user message
     * @param basePrompt existing prompt context
     * @param brain selected brain
     * @param knowledgeMode effective knowledge mode
     */
    public ToolCallingRequest(
            String requestId,
            String conversationId,
            String userMessage,
            String basePrompt,
            Brain brain,
            KnowledgeMode knowledgeMode
    ) {
        this(requestId, conversationId, userMessage, "", "", basePrompt, brain, knowledgeMode);
    }
}
