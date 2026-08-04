package com.jarvis.knowledge.context;

import java.util.List;

/**
 * Structured knowledge context prepared for future prompt generation.
 *
 * @param context assembled context text
 * @param sources sources used to build the context
 * @param sourceCount number of used sources
 * @param totalCharacters number of context characters
 * @param estimatedTokens rough token estimate
 * @param truncated whether context was truncated
 * @param buildTimeMs build time in milliseconds
 */
public record KnowledgeContext(
        String context,
        List<KnowledgeSource> sources,
        int sourceCount,
        int totalCharacters,
        int estimatedTokens,
        boolean truncated,
        long buildTimeMs
) {
}
