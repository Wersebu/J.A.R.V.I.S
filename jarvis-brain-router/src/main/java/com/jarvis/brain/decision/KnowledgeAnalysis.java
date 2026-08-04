package com.jarvis.brain.decision;

/**
 * Estimated knowledge requirements for a request.
 *
 * @param required whether local knowledge is probably required
 * @param estimatedDocumentCount estimated number of useful documents
 * @param estimatedContextSize estimated context size in characters
 * @param reason short explanation
 */
public record KnowledgeAnalysis(
        boolean required,
        int estimatedDocumentCount,
        int estimatedContextSize,
        String reason
) {
}
