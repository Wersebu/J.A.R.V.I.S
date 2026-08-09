package com.jarvis.knowledge.retrieval;

import com.jarvis.common.event.CognitiveEventBus;
import com.jarvis.common.event.CognitiveEvent;
import com.jarvis.common.event.CognitiveEventType;
import com.jarvis.knowledge.DocumentStatus;
import com.jarvis.knowledge.InMemoryKnowledgeIndex;
import com.jarvis.knowledge.KnowledgeDocument;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class KeywordKnowledgeRetrieverTest {

    @Test
    void ignoresShortPolishStopWordsThatWouldMatchUnrelatedDocuments() {
        InMemoryKnowledgeIndex index = new InMemoryKnowledgeIndex();
        index.upsert(document("People/Patrycja.md", "Patrycja", "Informacje o osobie i terminach."));
        KeywordKnowledgeRetriever retriever = new KeywordKnowledgeRetriever(index, noopEventBus());

        RetrievalResult result = retriever.retrieve("stresc mi rick i morty sezon 3 odc 5");

        assertThat(result.documents()).isEmpty();
        assertThat(result.documentsScanned()).isEqualTo(1);
    }

    @Test
    void keepsMeaningfulShortTechnologyKeywords() {
        InMemoryKnowledgeIndex index = new InMemoryKnowledgeIndex();
        index.upsert(document("Hardware/Computer.md", "Computer", "Local PC with RTX 3060."));
        KeywordKnowledgeRetriever retriever = new KeywordKnowledgeRetriever(index, noopEventBus());

        RetrievalResult result = retriever.retrieve("pc");

        assertThat(result.documents()).hasSize(1);
        assertThat(result.documents().getFirst().relativePath()).isEqualTo("Hardware/Computer.md");
    }

    private KnowledgeDocument document(String relativePath, String title, String preview) {
        return new KnowledgeDocument(
                UUID.randomUUID(),
                title,
                relativePath,
                relativePath.contains("/") ? relativePath.substring(0, relativePath.indexOf('/')) : "General",
                ".md",
                preview.length(),
                Instant.now(),
                Instant.now(),
                "sha",
                preview,
                DocumentStatus.INDEXED
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
}
