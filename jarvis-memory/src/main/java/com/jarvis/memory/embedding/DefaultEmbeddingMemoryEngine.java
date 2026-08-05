package com.jarvis.memory.embedding;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.common.embedding.EmbeddingProvider;
import com.jarvis.common.embedding.EmbeddingVector;
import com.jarvis.common.memory.MemoryCategory;
import com.jarvis.common.memory.MemoryPriority;
import com.jarvis.common.memory.MemoryRecord;
import com.jarvis.common.memory.MemoryType;
import com.jarvis.memory.cognitive.SemanticMemoryRecord;
import com.jarvis.memory.cognitive.SemanticMemoryStore;
import com.jarvis.memory.retrieval.MemoryQuery;
import com.jarvis.memory.retrieval.MemoryQueryNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Default embedding memory engine backed by SQLite and provider-generated vectors.
 */
@Service
public class DefaultEmbeddingMemoryEngine implements EmbeddingMemoryEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultEmbeddingMemoryEngine.class);
    private static final int SEARCH_LIMIT = 10;
    private static final double MIN_SCORE = 0.35d;

    private final EmbeddingProvider embeddingProvider;
    private final SemanticMemoryStore semanticStore;
    private final MemoryQueryNormalizer queryNormalizer;
    private final ObjectMapper objectMapper;

    /**
     * Creates the default embedding memory engine.
     *
     * @param embeddingProvider embedding provider
     * @param semanticStore semantic memory store
     * @param queryNormalizer query normalizer used only for category hints
     * @param objectMapper JSON mapper
     */
    public DefaultEmbeddingMemoryEngine(
            EmbeddingProvider embeddingProvider,
            SemanticMemoryStore semanticStore,
            MemoryQueryNormalizer queryNormalizer,
            ObjectMapper objectMapper
    ) {
        this.embeddingProvider = embeddingProvider;
        this.semanticStore = semanticStore;
        this.queryNormalizer = queryNormalizer;
        this.objectMapper = objectMapper;
    }

    @Override
    public void index(SemanticMemoryRecord record) {
        if (record == null) {
            return;
        }
        Optional<StoredMemoryEmbedding> existing = semanticStore.findEmbedding(record.id())
                .filter(embedding -> embedding.model().equals(embeddingProvider.model()));
        if (existing.isPresent()) {
            return;
        }
        EmbeddingVector embedding = embeddingProvider.embed(memoryText(record));
        semanticStore.updateEmbedding(
                record.id(),
                embedding.model(),
                embedding.dimension(),
                serialize(embedding.values())
        );
    }

    @Override
    public EmbeddingMemorySearchResult search(String query) {
        Instant started = Instant.now();
        EmbeddingVector queryEmbedding = embeddingProvider.embed(query);
        List<SemanticMemoryRecord> records = semanticStore.listAll();
        MemoryQuery normalizedQuery = queryNormalizer.normalize(query);
        List<EmbeddingMemoryMatch> matches = records.stream()
                .map(record -> score(record, queryEmbedding, normalizedQuery))
                .flatMap(Optional::stream)
                .filter(match -> match.score() >= MIN_SCORE)
                .sorted(Comparator.comparingDouble(EmbeddingMemoryMatch::score).reversed())
                .limit(SEARCH_LIMIT)
                .toList();
        long executionMs = Duration.between(started, Instant.now()).toMillis();
        LOGGER.info("""
                [JARVIS]
                MEMORY SEARCH

                Query:
                {}

                Embedding time:
                {} ms

                Candidates:
                {}

                Similarity:
                {}

                Selected:
                {}

                Execution:
                {} ms
                """,
                query,
                queryEmbedding.generationTimeMs(),
                records.size(),
                similarityLog(matches),
                matches.isEmpty() ? "None" : matches.getFirst().memory().content(),
                executionMs);
        return new EmbeddingMemorySearchResult(
                query,
                queryEmbedding.model(),
                queryEmbedding.generationTimeMs(),
                executionMs,
                records.size(),
                matches
        );
    }

    private Optional<EmbeddingMemoryMatch> score(
            SemanticMemoryRecord record,
            EmbeddingVector queryEmbedding,
            MemoryQuery normalizedQuery
    ) {
        StoredMemoryEmbedding memoryEmbedding = embedding(record);
        if (memoryEmbedding.vector().isEmpty()) {
            return Optional.empty();
        }
        double similarity = cosine(queryEmbedding.values(), memoryEmbedding.vector());
        double confidence = clamp(record.confidence()) * 0.10d;
        double priority = priorityScore(record.priority()) * 0.07d;
        double freshness = freshness(record.updatedAt()) * 0.05d;
        double category = categoryScore(record, normalizedQuery) * 0.06d;
        double score = clamp(similarity * 0.72d + confidence + priority + freshness + category);
        return Optional.of(new EmbeddingMemoryMatch(
                toMemory(record),
                score,
                similarity,
                "cosine=%.4f confidence=%.2f priority=%s category=%s".formatted(
                        similarity,
                        record.confidence(),
                        record.priority(),
                        record.category()
                )
        ));
    }

    private StoredMemoryEmbedding embedding(SemanticMemoryRecord record) {
        Optional<StoredMemoryEmbedding> stored = semanticStore.findEmbedding(record.id())
                .filter(embedding -> embedding.model().equals(embeddingProvider.model()));
        if (stored.isPresent()) {
            return stored.get();
        }
        index(record);
        return semanticStore.findEmbedding(record.id())
                .orElse(new StoredMemoryEmbedding(record.id(), embeddingProvider.model(), 0, List.of()));
    }

    private String memoryText(SemanticMemoryRecord record) {
        return "%s %s %s".formatted(record.subject(), record.predicate(), record.value()).strip();
    }

    private MemoryRecord toMemory(SemanticMemoryRecord record) {
        return new MemoryRecord(
                record.id(),
                MemoryType.SEMANTIC,
                record.subject() + " " + record.predicate(),
                memoryText(record),
                record.confidence(),
                record.priority(),
                record.category(),
                record.createdAt(),
                record.updatedAt(),
                record.sourceConversation()
        );
    }

    private String serialize(List<Double> vector) {
        try {
            return objectMapper.writeValueAsString(vector);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize memory embedding", exception);
        }
    }

    private double cosine(List<Double> left, List<Double> right) {
        if (left.isEmpty() || right.isEmpty() || left.size() != right.size()) {
            return 0.0d;
        }
        double dot = 0.0d;
        double leftNorm = 0.0d;
        double rightNorm = 0.0d;
        for (int index = 0; index < left.size(); index++) {
            double leftValue = left.get(index);
            double rightValue = right.get(index);
            dot += leftValue * rightValue;
            leftNorm += leftValue * leftValue;
            rightNorm += rightValue * rightValue;
        }
        if (leftNorm == 0.0d || rightNorm == 0.0d) {
            return 0.0d;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
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

    private double categoryScore(SemanticMemoryRecord record, MemoryQuery query) {
        if (query.preferredCategories().contains(record.category())) {
            return 1.0d;
        }
        if (record.category() == MemoryCategory.SEMANTIC) {
            return 0.4d;
        }
        return 0.0d;
    }

    private double clamp(double value) {
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    private String similarityLog(List<EmbeddingMemoryMatch> matches) {
        if (matches.isEmpty()) {
            return "None";
        }
        StringBuilder builder = new StringBuilder();
        for (EmbeddingMemoryMatch match : matches) {
            builder.append(match.memory().content())
                    .append(" -> ")
                    .append("%.2f".formatted(match.similarity()))
                    .append('\n');
        }
        return builder.toString();
    }
}
