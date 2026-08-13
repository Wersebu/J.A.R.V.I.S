package com.jarvis.knowledge.retrieval;

import com.jarvis.common.embedding.EmbeddingProvider;
import com.jarvis.common.embedding.EmbeddingVector;
import com.jarvis.common.event.CognitiveEvent;
import com.jarvis.common.event.CognitiveEventBus;
import com.jarvis.common.event.CognitiveEventType;
import com.jarvis.knowledge.DocumentStatus;
import com.jarvis.knowledge.InMemoryKnowledgeIndex;
import com.jarvis.knowledge.KnowledgeDocument;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests proving hybrid retrieval finds documents that pure keyword matching cannot —
 * because the query and the document share meaning but no literal words — and that it degrades
 * gracefully to lexical-only results when the embedding provider is unavailable instead of
 * failing the whole search.
 */
class DefaultHybridKnowledgeRetrieverTest {

    @Test
    void semanticMatchSurfacesDocumentWithNoLiteralKeywordOverlap() {
        InMemoryKnowledgeIndex index = new InMemoryKnowledgeIndex();
        index.upsert(document("hardware/graphics_card.txt", "graphics_card",
                "RTX 4060 Ti 16 GB graphics card"));
        KeywordKnowledgeRetriever keyword = new KeywordKnowledgeRetriever(index, noopEventBus());
        DefaultEmbeddingKnowledgeRetriever embedding = new DefaultEmbeddingKnowledgeRetriever(index, new SynonymAwareFakeEmbeddingProvider());
        DefaultHybridKnowledgeRetriever hybrid = new DefaultHybridKnowledgeRetriever(keyword, embedding);

        String query = "GPU installed in J.A.R.V.I.S server";

        RetrievalResult keywordOnly = keyword.retrieve(query);
        assertThat(keywordOnly.documents()).isEmpty();

        RetrievalResult hybridResult = hybrid.retrieve(query);
        assertThat(hybridResult.documents()).anySatisfy(
                doc -> assertThat(doc.relativePath()).isEqualTo("hardware/graphics_card.txt"));
    }

    @Test
    void fallsBackToLexicalOnlyWhenEmbeddingProviderFails() {
        InMemoryKnowledgeIndex index = new InMemoryKnowledgeIndex();
        index.upsert(document("Hardware/Computer.md", "Computer", "Local PC with RTX 3060."));
        KeywordKnowledgeRetriever keyword = new KeywordKnowledgeRetriever(index, noopEventBus());
        DefaultEmbeddingKnowledgeRetriever embedding = new DefaultEmbeddingKnowledgeRetriever(index, new FailingEmbeddingProvider());
        DefaultHybridKnowledgeRetriever hybrid = new DefaultHybridKnowledgeRetriever(keyword, embedding);

        RetrievalResult result = hybrid.retrieve("pc");

        assertThat(result.documents()).hasSize(1);
        assertThat(result.documents().getFirst().relativePath()).isEqualTo("Hardware/Computer.md");
    }

    private KnowledgeDocument document(String relativePath, String title, String preview) {
        return new KnowledgeDocument(
                UUID.randomUUID(), title, relativePath,
                relativePath.contains("/") ? relativePath.substring(0, relativePath.indexOf('/')) : "General",
                ".txt", preview.length(), Instant.now(), Instant.now(), "sha", preview, DocumentStatus.INDEXED
        );
    }

    private CognitiveEventBus noopEventBus() {
        return new CognitiveEventBus() {
            @Override
            public void startRequest(String requestId, String conversationId, Consumer<CognitiveEvent> sink) {
            }

            @Override
            public void finishRequest() {
            }

            @Override
            public void updateBrain(com.jarvis.common.ai.BrainType brain, String model) {
            }

            @Override
            public void publish(CognitiveEventType event, String status, String message, String nodeId, Map<String, Object> metadata) {
            }
        };
    }

    /**
     * Deterministic fake embedding space with one axis that treats "gpu"/"graphics"/"card" as
     * synonyms and another for "server", so a query using "GPU" scores high similarity against a
     * document that only says "graphics card" — without sharing a single literal token.
     */
    private static final class SynonymAwareFakeEmbeddingProvider implements EmbeddingProvider {

        @Override
        public String provider() {
            return "fake";
        }

        @Override
        public String model() {
            return "fake-synonym-model";
        }

        @Override
        public EmbeddingVector embed(String text) {
            String normalized = text.toLowerCase(Locale.ROOT);
            double gpu = containsAny(normalized, "gpu", "graphics", "card") ? 1.0 : 0.0;
            double server = normalized.contains("server") ? 1.0 : 0.0;
            double other = gpu == 0.0 && server == 0.0 && !normalized.isBlank() ? 1.0 : 0.0;
            return new EmbeddingVector(model(), List.of(gpu, server, other), 0);
        }

        private boolean containsAny(String value, String... needles) {
            for (String needle : needles) {
                if (value.contains(needle)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final class FailingEmbeddingProvider implements EmbeddingProvider {

        @Override
        public String provider() {
            return "failing";
        }

        @Override
        public String model() {
            return "unavailable";
        }

        @Override
        public EmbeddingVector embed(String text) {
            throw new RuntimeException("Embedding provider unavailable");
        }
    }
}
