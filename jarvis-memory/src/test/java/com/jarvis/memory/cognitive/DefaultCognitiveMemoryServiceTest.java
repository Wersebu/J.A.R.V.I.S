package com.jarvis.memory.cognitive;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests deterministic cognitive memory retrieval.
 */
class DefaultCognitiveMemoryServiceTest {

    @Test
    void retrievesGpuMemoryUsingGraphicsCardSynonym() {
        InMemorySemanticStore semanticStore = new InMemorySemanticStore();
        DefaultCognitiveMemoryService service = service(semanticStore);
        semanticStore.save(new SemanticMemoryRecord(
                UUID.randomUUID(),
                "user",
                "owns",
                "RTX3060",
                0.94d,
                Instant.now(),
                Instant.now(),
                "test"
        ));

        var result = service.search("What graphics card do I have?");

        assertThat(result.memories())
                .extracting(memory -> memory.content())
                .anyMatch(content -> content.contains("RTX3060"));
    }

    @Test
    void retrievesCarMemoryUsingDriveSynonym() {
        InMemorySemanticStore semanticStore = new InMemorySemanticStore();
        DefaultCognitiveMemoryService service = service(semanticStore);
        semanticStore.save(new SemanticMemoryRecord(
                UUID.randomUUID(),
                "user",
                "owns",
                "Audi A8",
                0.94d,
                Instant.now(),
                Instant.now(),
                "test"
        ));

        var result = service.search("What car do I drive?");

        assertThat(result.memories())
                .extracting(memory -> memory.content())
                .anyMatch(content -> content.contains("Audi A8"));
    }

    private DefaultCognitiveMemoryService service(InMemorySemanticStore semanticStore) {
        return new DefaultCognitiveMemoryService(
                semanticStore,
                new InMemoryEpisodicStore(),
                new InMemoryProceduralStore(),
                new DeterministicMemoryClassifier()
        );
    }

    private static final class InMemorySemanticStore implements SemanticMemoryStore {
        private final List<SemanticMemoryRecord> records = new ArrayList<>();

        @Override
        public void save(SemanticMemoryRecord record) {
            records.add(record);
        }

        @Override
        public void update(SemanticMemoryRecord record) {
            delete(record.id());
            records.add(record);
        }

        @Override
        public Optional<SemanticMemoryRecord> findExact(String subject, String predicate, String value) {
            return records.stream()
                    .filter(record -> record.subject().equalsIgnoreCase(subject))
                    .filter(record -> record.predicate().equalsIgnoreCase(predicate))
                    .filter(record -> record.value().equalsIgnoreCase(value))
                    .findFirst();
        }

        @Override
        public List<SemanticMemoryRecord> search(String query, int limit) {
            return records.stream().limit(limit).toList();
        }

        @Override
        public List<SemanticMemoryRecord> listAll() {
            return List.copyOf(records);
        }

        @Override
        public boolean delete(UUID id) {
            return records.removeIf(record -> record.id().equals(id));
        }
    }

    private static final class InMemoryEpisodicStore implements EpisodicMemoryStore {
        @Override
        public void save(EpisodicMemoryRecord record) {
        }

        @Override
        public List<EpisodicMemoryRecord> search(String query, int limit) {
            return List.of();
        }

        @Override
        public List<EpisodicMemoryRecord> listAll() {
            return List.of();
        }

        @Override
        public boolean delete(UUID id) {
            return false;
        }
    }

    private static final class InMemoryProceduralStore implements ProceduralMemoryStore {
        @Override
        public void save(ProceduralMemoryRecord record) {
        }

        @Override
        public void update(ProceduralMemoryRecord record) {
        }

        @Override
        public Optional<ProceduralMemoryRecord> findByName(String name) {
            return Optional.empty();
        }

        @Override
        public List<ProceduralMemoryRecord> search(String query, int limit) {
            return List.of();
        }

        @Override
        public List<ProceduralMemoryRecord> listAll() {
            return List.of();
        }

        @Override
        public boolean delete(UUID id) {
            return false;
        }
    }
}
