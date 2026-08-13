package com.jarvis.knowledge.retrieval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Combines lexical keyword scoring with semantic embedding similarity.
 *
 * <p>A document can be found either because its title/path/preview shares literal keywords with
 * the query (lexical), or because its meaning is close to the query even without shared words
 * (semantic) — for example "GPU installed in the server" matching a document whose text only says
 * "RTX 4060 Ti 16 GB". Both signals are combined so a strong match in either dimension surfaces
 * the document; if the embedding provider is unavailable, retrieval degrades to lexical-only
 * instead of failing.
 */
@Service
@Primary
public class DefaultHybridKnowledgeRetriever implements HybridKnowledgeRetriever {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultHybridKnowledgeRetriever.class);
    private static final int MAX_RESULTS = 10;
    private static final double SEMANTIC_WEIGHT = 0.5d;

    private final KeywordKnowledgeRetriever keywordRetriever;
    private final EmbeddingKnowledgeRetriever embeddingRetriever;

    /**
     * Creates the hybrid retriever.
     *
     * @param keywordRetriever lexical retriever
     * @param embeddingRetriever semantic retriever
     */
    public DefaultHybridKnowledgeRetriever(KeywordKnowledgeRetriever keywordRetriever, EmbeddingKnowledgeRetriever embeddingRetriever) {
        this.keywordRetriever = keywordRetriever;
        this.embeddingRetriever = embeddingRetriever;
    }

    @Override
    public RetrievalResult retrieve(String query) {
        RetrievalResult keywordResult = keywordRetriever.retrieve(query);
        RetrievalResult semanticResult = safeSemanticRetrieve(query);

        Map<UUID, RetrievalDocument> byId = new LinkedHashMap<>();
        Map<UUID, Integer> combinedScores = new HashMap<>();
        for (RetrievalDocument document : keywordResult.documents()) {
            byId.put(document.documentId(), document);
            combinedScores.merge(document.documentId(), document.score(), Integer::sum);
        }
        for (RetrievalDocument document : semanticResult.documents()) {
            byId.putIfAbsent(document.documentId(), document);
            combinedScores.merge(document.documentId(), (int) Math.round(document.score() * SEMANTIC_WEIGHT), Integer::sum);
        }

        List<RetrievalDocument> ordered = byId.values().stream()
                .map(document -> withScore(document, combinedScores.getOrDefault(document.documentId(), document.score())))
                .sorted(Comparator.comparingInt(RetrievalDocument::score).reversed()
                        .thenComparing(RetrievalDocument::relativePath))
                .limit(MAX_RESULTS)
                .toList();

        long executionTimeMs = keywordResult.executionTimeMs() + semanticResult.executionTimeMs();
        long documentsScanned = Math.max(keywordResult.documentsScanned(), semanticResult.documentsScanned());
        String normalizedQuery = query == null ? "" : query.strip();
        LOGGER.info("[JARVIS] Hybrid knowledge retrieval query=\"{}\" lexicalHits={} semanticHits={} combinedResults={}",
                normalizedQuery, keywordResult.documents().size(), semanticResult.documents().size(), ordered.size());
        return new RetrievalResult(normalizedQuery, executionTimeMs, documentsScanned, ordered);
    }

    private RetrievalResult safeSemanticRetrieve(String query) {
        try {
            return embeddingRetriever.retrieve(query);
        } catch (RuntimeException exception) {
            LOGGER.warn("[JARVIS] Semantic retrieval failed, falling back to lexical-only: {}", exception.getMessage());
            return new RetrievalResult(query == null ? "" : query.strip(), 0, 0, List.of());
        }
    }

    private RetrievalDocument withScore(RetrievalDocument document, int score) {
        return new RetrievalDocument(document.documentId(), document.title(), document.category(),
                document.relativePath(), score, document.preview());
    }
}
