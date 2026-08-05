package com.jarvis.common.memory;

import java.util.List;

/**
 * Result returned by memory search.
 *
 * @param query original query
 * @param executionTimeMs search execution time in milliseconds
 * @param memories matching memories
 * @param matches scored matching memories
 * @param normalizedQuery normalized query tokens
 * @param candidateCount number of candidate memories before final scoring limit
 * @param embeddingModel embedding model used for semantic retrieval
 * @param embeddingTimeMs query embedding generation time in milliseconds
 */
public record MemorySearchResult(
        String query,
        long executionTimeMs,
        List<MemoryRecord> memories,
        List<MemorySearchMatch> matches,
        List<String> normalizedQuery,
        int candidateCount,
        String embeddingModel,
        long embeddingTimeMs
) {

    /**
     * Creates a search result without detailed scores.
     *
     * @param query original query
     * @param executionTimeMs search execution time in milliseconds
     * @param memories matching memories
     */
    public MemorySearchResult(String query, long executionTimeMs, List<MemoryRecord> memories) {
        this(query, executionTimeMs, memories, List.of(), List.of(), memories == null ? 0 : memories.size(), "", 0L);
    }

    /**
     * Creates a search result with scores.
     *
     * @param query original query
     * @param executionTimeMs search execution time in milliseconds
     * @param memories matching memories
     * @param matches scored matching memories
     */
    public MemorySearchResult(String query, long executionTimeMs, List<MemoryRecord> memories, List<MemorySearchMatch> matches) {
        this(query, executionTimeMs, memories, matches, List.of(), memories == null ? 0 : memories.size(), "", 0L);
    }

    /**
     * Creates an immutable search result.
     *
     * @param query original query
     * @param executionTimeMs search execution time in milliseconds
     * @param memories matching memories
     */
    public MemorySearchResult {
        memories = memories == null ? List.of() : List.copyOf(memories);
        matches = matches == null ? List.of() : List.copyOf(matches);
        normalizedQuery = normalizedQuery == null ? List.of() : List.copyOf(normalizedQuery);
        embeddingModel = embeddingModel == null ? "" : embeddingModel;
    }
}
