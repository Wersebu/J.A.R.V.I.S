package com.jarvis.tools.runtime;

import com.jarvis.common.ai.Brain;
import com.jarvis.common.knowledge.KnowledgeMode;

/**
 * Input for a native tool-calling loop.
 *
 * @param requestId request identifier
 * @param conversationId conversation identifier
 * @param userMessage latest user message
 * @param basePrompt existing prompt context
 * @param brain selected brain
 * @param knowledgeMode effective knowledge mode
 */
public record ToolCallingRequest(
        String requestId,
        String conversationId,
        String userMessage,
        String basePrompt,
        Brain brain,
        KnowledgeMode knowledgeMode
) {
}
