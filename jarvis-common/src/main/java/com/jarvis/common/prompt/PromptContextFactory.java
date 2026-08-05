package com.jarvis.common.prompt;

import com.jarvis.common.context.KnowledgeContext;
import com.jarvis.common.memory.CognitiveMemoryContext;
import com.jarvis.common.memory.ConversationMessage;

import java.util.List;

/**
 * Builds source-aware prompt context from pipeline evidence.
 */
public interface PromptContextFactory {

    /**
     * Creates prompt context.
     *
     * @param userMessage current user message
     * @param memoryContext retrieved memory context
     * @param knowledgeContext retrieved knowledge context
     * @param conversation current conversation history
     * @return prompt context
     */
    PromptContext create(
            String userMessage,
            CognitiveMemoryContext memoryContext,
            KnowledgeContext knowledgeContext,
            List<ConversationMessage> conversation
    );
}
