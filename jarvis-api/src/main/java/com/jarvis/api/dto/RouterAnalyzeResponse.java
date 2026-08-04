package com.jarvis.api.dto;

/**
 * Router decision debug response.
 *
 * @param taskType detected task type
 * @param complexity request complexity
 * @param knowledgeRequired whether knowledge is required
 * @param estimatedKnowledgeDocuments estimated knowledge documents
 * @param estimatedPromptTokens estimated prompt tokens
 * @param brain selected brain
 * @param model selected model
 * @param reasoningLevel selected reasoning level
 * @param reason explanation
 * @param confidence task confidence
 */
public record RouterAnalyzeResponse(
        String taskType,
        int complexity,
        boolean knowledgeRequired,
        int estimatedKnowledgeDocuments,
        int estimatedPromptTokens,
        String brain,
        String model,
        String reasoningLevel,
        String reason,
        double confidence
) {
}
