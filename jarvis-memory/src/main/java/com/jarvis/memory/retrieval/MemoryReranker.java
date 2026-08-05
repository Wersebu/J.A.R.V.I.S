package com.jarvis.memory.retrieval;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reranks close memory candidates.
 */
public interface MemoryReranker {

    /**
     * Selects the best matching memory id.
     *
     * @param query original query
     * @param candidates scored candidates
     * @return selected id
     */
    Optional<UUID> rerank(String query, List<MemoryScore> candidates);
}
