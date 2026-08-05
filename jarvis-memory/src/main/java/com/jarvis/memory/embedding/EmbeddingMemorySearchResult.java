package com.jarvis.memory.embedding;

import java.util.List;

/**
 * Result of semantic embedding memory search.
 *
 * @param query original query
 * @param embeddingModel embedding model
 * @param embeddingTimeMs query embedding generation time
 * @param executionTimeMs full search execution time
 * @param candidates candidates compared
 * @param matches top matching memories
 */
public record EmbeddingMemorySearchResult(
        String query,
        String embeddingModel,
        long embeddingTimeMs,
        long executionTimeMs,
        int candidates,
        List<EmbeddingMemoryMatch> matches
) {

    /**
     * Creates an immutable search result.
     */
    public EmbeddingMemorySearchResult {
        matches = matches == null ? List.of() : List.copyOf(matches);
    }
}
