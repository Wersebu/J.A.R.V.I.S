package com.jarvis.common.context;

import java.util.List;

/**
 * Metadata describing knowledge injected into a generation request.
 *
 * @param conversationId conversation identifier
 * @param documentsUsed number of documents used
 * @param titles source titles
 * @param categories source categories
 * @param charactersInjected injected context character count
 * @param estimatedTokens estimated injected context tokens
 * @param generationTime generation time in milliseconds
 */
public record KnowledgeUsage(
        String conversationId,
        int documentsUsed,
        List<String> titles,
        List<String> categories,
        int charactersInjected,
        int estimatedTokens,
        long generationTime
) {

    /**
     * Creates usage metadata from a knowledge context.
     *
     * @param conversationId conversation identifier
     * @param knowledgeContext knowledge context
     * @param generationTime generation time in milliseconds
     * @return usage metadata
     */
    public static KnowledgeUsage from(String conversationId, KnowledgeContext knowledgeContext, long generationTime) {
        return new KnowledgeUsage(
                conversationId,
                knowledgeContext.sourceCount(),
                knowledgeContext.sources().stream().map(KnowledgeSource::title).toList(),
                knowledgeContext.sources().stream().map(KnowledgeSource::category).distinct().toList(),
                knowledgeContext.totalCharacters(),
                knowledgeContext.estimatedTokens(),
                generationTime
        );
    }
}
