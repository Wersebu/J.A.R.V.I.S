package com.jarvis.memory.retrieval;

import com.jarvis.common.memory.MemoryPriority;
import com.jarvis.common.memory.MemoryRecord;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Token-based memory scorer that can later be replaced by an embedding scorer.
 */
@Component
public class TokenMemoryScorer implements MemoryScorer {

    private final MemoryQueryNormalizer normalizer;

    /**
     * Creates the scorer.
     *
     * @param normalizer query normalizer
     */
    public TokenMemoryScorer(MemoryQueryNormalizer normalizer) {
        this.normalizer = normalizer;
    }

    @Override
    public MemoryScore score(MemoryQuery query, MemoryRecord memory) {
        Set<String> queryTokens = new LinkedHashSet<>(query.tokens());
        MemoryQuery memoryQuery = normalizer.normalize(memory.title() + " " + memory.content());
        Set<String> memoryTokens = new LinkedHashSet<>(memoryQuery.tokens());
        if (queryTokens.isEmpty() || memoryTokens.isEmpty()) {
            return new MemoryScore(memory, 0.0d, "empty tokens");
        }

        int overlap = 0;
        for (String token : queryTokens) {
            if (memoryTokens.contains(token)) {
                overlap++;
            }
        }
        double overlapScore = overlap / (double) Math.max(1, queryTokens.size());
        double categoryScore = query.preferredCategories().contains(memory.category())
                || intersects(query.preferredCategories(), memoryQuery.preferredCategories()) ? 0.22d : 0.0d;
        double confidenceScore = clamp(memory.confidence()) * 0.12d;
        double priorityScore = priorityScore(memory.priority()) * 0.08d;
        double freshnessScore = freshness(memory.updatedAt()) * 0.06d;
        double semanticScore = semanticSimilarity(queryTokens, memoryTokens) * 0.24d;
        double score = clamp(overlapScore * 0.28d + semanticScore + categoryScore + confidenceScore + priorityScore + freshnessScore);
        return new MemoryScore(memory, score, "overlap=%s category=%s confidence=%.2f priority=%s"
                .formatted(overlap, memory.category(), memory.confidence(), memory.priority()));
    }

    private <T> boolean intersects(Set<T> left, Set<T> right) {
        for (T value : right) {
            if (left.contains(value)) {
                return true;
            }
        }
        return false;
    }

    private double semanticSimilarity(Set<String> queryTokens, Set<String> memoryTokens) {
        Set<String> intersection = new LinkedHashSet<>(queryTokens);
        intersection.retainAll(memoryTokens);
        Set<String> union = new LinkedHashSet<>(queryTokens);
        union.addAll(memoryTokens);
        return union.isEmpty() ? 0.0d : intersection.size() / (double) union.size();
    }

    private double priorityScore(MemoryPriority priority) {
        return switch (priority == null ? MemoryPriority.NORMAL : priority) {
            case CRITICAL -> 1.0d;
            case HIGH -> 0.85d;
            case NORMAL -> 0.65d;
            case LOW -> 0.35d;
            case TEMPORARY -> 0.1d;
        };
    }

    private double freshness(Instant updatedAt) {
        if (updatedAt == null) {
            return 0.2d;
        }
        long days = Math.max(0, Duration.between(updatedAt, Instant.now()).toDays());
        return 1.0d / (1.0d + days / 90.0d);
    }

    private double clamp(double value) {
        return Math.max(0.0d, Math.min(1.0d, value));
    }
}
