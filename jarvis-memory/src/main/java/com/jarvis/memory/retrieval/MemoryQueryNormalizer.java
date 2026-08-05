package com.jarvis.memory.retrieval;

/**
 * Creates normalized query variants for memory retrieval.
 */
public interface MemoryQueryNormalizer {

    /**
     * Normalizes an original user query.
     *
     * @param query original query
     * @return normalized query
     */
    MemoryQuery normalize(String query);
}
