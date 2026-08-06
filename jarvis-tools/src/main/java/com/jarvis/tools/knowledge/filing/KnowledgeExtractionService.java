package com.jarvis.tools.knowledge.filing;

/**
 * Extracts canonical knowledge from a conversational user message.
 */
public interface KnowledgeExtractionService {

    /**
     * Extracts meaningful knowledge.
     *
     * @param sourceMessage original user message
     * @return extracted knowledge
     */
    ExtractedKnowledge extract(String sourceMessage);
}
