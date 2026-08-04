package com.jarvis.common.memory;

import java.util.List;

/**
 * Result returned by memory search.
 *
 * @param query original query
 * @param executionTimeMs search execution time in milliseconds
 * @param memories matching memories
 */
public record MemorySearchResult(String query, long executionTimeMs, List<MemoryRecord> memories) {

    /**
     * Creates an immutable search result.
     *
     * @param query original query
     * @param executionTimeMs search execution time in milliseconds
     * @param memories matching memories
     */
    public MemorySearchResult {
        memories = memories == null ? List.of() : List.copyOf(memories);
    }
}
