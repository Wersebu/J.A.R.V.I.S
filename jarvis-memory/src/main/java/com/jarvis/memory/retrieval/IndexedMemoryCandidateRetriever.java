package com.jarvis.memory.retrieval;

import com.jarvis.common.memory.MemoryRecord;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Token/category candidate selector for memory retrieval.
 */
@Component
public class IndexedMemoryCandidateRetriever implements MemoryCandidateRetriever {

    private final MemoryQueryNormalizer normalizer;

    /**
     * Creates the retriever.
     *
     * @param normalizer query normalizer
     */
    public IndexedMemoryCandidateRetriever(MemoryQueryNormalizer normalizer) {
        this.normalizer = normalizer;
    }

    @Override
    public List<MemoryRecord> candidates(MemoryQuery query, List<MemoryRecord> memories) {
        Set<MemoryRecord> candidates = new LinkedHashSet<>();
        Set<String> queryTokens = Set.copyOf(query.tokens());
        for (MemoryRecord memory : memories) {
            MemoryQuery memoryQuery = normalizer.normalize(memory.title() + " " + memory.content());
            boolean categoryMatch = query.preferredCategories().contains(memory.category())
                    || overlaps(query.preferredCategories(), memoryQuery.preferredCategories());
            boolean tokenMatch = overlaps(queryTokens, memoryQuery.tokens());
            if (categoryMatch || tokenMatch) {
                candidates.add(memory);
            }
        }
        return candidates.stream().limit(40).toList();
    }

    private <T> boolean overlaps(Set<T> left, Iterable<T> right) {
        for (T token : right) {
            if (left.contains(token)) {
                return true;
            }
        }
        return false;
    }
}
