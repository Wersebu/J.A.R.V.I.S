package com.jarvis.knowledge.retrieval;

import com.jarvis.common.embedding.EmbeddingProvider;
import com.jarvis.common.embedding.EmbeddingVector;
import com.jarvis.knowledge.KnowledgeDocument;
import com.jarvis.knowledge.KnowledgeIndex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Semantic retrieval over indexed knowledge document metadata using an {@link EmbeddingProvider}.
 *
 * <p>Each document is embedded from its title, category, path, and preview text (the same text
 * already indexed for keyword search) so it can match a query that shares no literal keywords but
 * is semantically related. Embeddings are cached in memory per document and invalidated whenever
 * the document's content hash changes, so re-embedding only happens when a file is actually new
 * or modified.
 */
@Service
public class DefaultEmbeddingKnowledgeRetriever implements EmbeddingKnowledgeRetriever {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultEmbeddingKnowledgeRetriever.class);
    private static final int MAX_RESULTS = 10;
    private static final double MIN_SIMILARITY = 0.30d;

    private final KnowledgeIndex knowledgeIndex;
    private final EmbeddingProvider embeddingProvider;
    private final Map<UUID, CachedEmbedding> cache = new ConcurrentHashMap<>();

    /**
     * Creates the semantic retriever.
     *
     * @param knowledgeIndex metadata index
     * @param embeddingProvider embedding provider
     */
    public DefaultEmbeddingKnowledgeRetriever(KnowledgeIndex knowledgeIndex, EmbeddingProvider embeddingProvider) {
        this.knowledgeIndex = knowledgeIndex;
        this.embeddingProvider = embeddingProvider;
    }

    @Override
    public RetrievalResult retrieve(String query) {
        long started = System.nanoTime();
        String normalizedQuery = query == null ? "" : query.strip();
        List<KnowledgeDocument> documents = knowledgeIndex.list();
        if (normalizedQuery.isBlank()) {
            return new RetrievalResult(normalizedQuery, 0, documents.size(), List.of());
        }

        EmbeddingVector queryEmbedding;
        try {
            queryEmbedding = embeddingProvider.embed(normalizedQuery);
        } catch (RuntimeException exception) {
            long elapsedMs = (System.nanoTime() - started) / 1_000_000;
            LOGGER.warn("[JARVIS] Semantic knowledge retrieval unavailable, embedding provider failed: {}", exception.getMessage());
            return new RetrievalResult(normalizedQuery, elapsedMs, documents.size(), List.of());
        }
        if (queryEmbedding.values().isEmpty()) {
            long elapsedMs = (System.nanoTime() - started) / 1_000_000;
            return new RetrievalResult(normalizedQuery, elapsedMs, documents.size(), List.of());
        }

        List<RetrievalDocument> results = documents.stream()
                .map(document -> scored(document, queryEmbedding))
                .filter(scored -> scored != null)
                .sorted(Comparator.comparingInt(RetrievalDocument::score).reversed())
                .limit(MAX_RESULTS)
                .toList();

        long executionTimeMs = (System.nanoTime() - started) / 1_000_000;
        LOGGER.info("[JARVIS] Semantic knowledge retrieval query=\"{}\" executionTimeMs={} documentsScanned={} resultsReturned={}",
                normalizedQuery, executionTimeMs, documents.size(), results.size());
        return new RetrievalResult(normalizedQuery, executionTimeMs, documents.size(), results);
    }

    private RetrievalDocument scored(KnowledgeDocument document, EmbeddingVector queryEmbedding) {
        EmbeddingVector documentEmbedding = embeddingFor(document);
        if (documentEmbedding == null || documentEmbedding.values().isEmpty()) {
            return null;
        }
        double similarity = cosine(queryEmbedding.values(), documentEmbedding.values());
        if (similarity < MIN_SIMILARITY) {
            return null;
        }
        int score = (int) Math.round(similarity * 1000.0d);
        return new RetrievalDocument(document.id(), document.title(), document.category(),
                document.relativePath(), score, document.preview());
    }

    private EmbeddingVector embeddingFor(KnowledgeDocument document) {
        CachedEmbedding cached = cache.get(document.id());
        if (cached != null && cached.contentHash.equals(document.sha256()) && cached.model.equals(embeddingProvider.model())) {
            return cached.vector;
        }
        String text = embeddingText(document);
        if (text.isBlank()) {
            return null;
        }
        try {
            EmbeddingVector vector = embeddingProvider.embed(text);
            cache.put(document.id(), new CachedEmbedding(document.sha256(), embeddingProvider.model(), vector));
            return vector;
        } catch (RuntimeException exception) {
            LOGGER.warn("[JARVIS] Failed to embed knowledge document {}: {}", document.relativePath(), exception.getMessage());
            return null;
        }
    }

    private String embeddingText(KnowledgeDocument document) {
        return "%s %s %s %s".formatted(
                safe(document.title()), safe(document.category()), safe(document.relativePath()), safe(document.preview())
        ).strip();
    }

    private String safe(String value) {
        return value == null ? "" : value;
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

    private record CachedEmbedding(String contentHash, String model, EmbeddingVector vector) {
    }
}
