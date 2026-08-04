package com.jarvis.common.memory;

import java.util.List;

/**
 * Result returned by memory search.
 *
 * @param query original query
 * @param executionTimeMs search execution time in milliseconds
 * @param memories matching memories
 * @param matches scored matching memories
 */
public record MemorySearchResult(
        String query,
        long executionTimeMs,
        List<MemoryRecord> memories,
        List<MemorySearchMatch> matches
) {

    /**
     * Creates a search result without detailed scores.
     *
     * @param query original query
     * @param executionTimeMs search execution time in milliseconds
     * @param memories matching memories
     */
    public MemorySearchResult(String query, long executionTimeMs, List<MemoryRecord> memories) {
        this(query, executionTimeMs, memories, List.of());
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
    }
}
