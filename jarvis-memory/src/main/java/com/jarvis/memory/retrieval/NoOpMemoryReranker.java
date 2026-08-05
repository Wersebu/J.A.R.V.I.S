package com.jarvis.memory.retrieval;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reranker used when no AI provider should be invoked.
 */
public class NoOpMemoryReranker implements MemoryReranker {

    @Override
    public Optional<UUID> rerank(String query, List<MemoryScore> candidates) {
        return Optional.empty();
    }
}
