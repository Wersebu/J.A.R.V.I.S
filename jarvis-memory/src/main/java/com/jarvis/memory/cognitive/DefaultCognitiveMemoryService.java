package com.jarvis.memory.cognitive;

import com.jarvis.common.memory.CognitiveMemoryContext;
import com.jarvis.common.memory.MemoryRecord;
import com.jarvis.common.memory.MemorySearchResult;
import com.jarvis.common.memory.MemoryType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Default cognitive memory service backed by structured SQLite stores.
 */
@Service
public class DefaultCognitiveMemoryService implements CognitiveMemoryService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultCognitiveMemoryService.class);
    private static final int SEARCH_LIMIT = 10;
    private static final int CONTEXT_LIMIT = 6_000;

    private final SemanticMemoryStore semanticStore;
    private final EpisodicMemoryStore episodicStore;
    private final ProceduralMemoryStore proceduralStore;
    private final DeterministicMemoryClassifier classifier;

    /**
     * Creates the default cognitive memory service.
     *
     * @param semanticStore semantic memory store
     * @param episodicStore episodic memory store
     * @param proceduralStore procedural memory store
     * @param classifier deterministic classifier
     */
    public DefaultCognitiveMemoryService(
            SemanticMemoryStore semanticStore,
            EpisodicMemoryStore episodicStore,
            ProceduralMemoryStore proceduralStore,
            DeterministicMemoryClassifier classifier
    ) {
        this.semanticStore = semanticStore;
        this.episodicStore = episodicStore;
        this.proceduralStore = proceduralStore;
        this.classifier = classifier;
    }

    @Override
    public List<MemoryRecord> listAll() {
        List<MemoryRecord> memories = new ArrayList<>();
        semanticStore.listAll().forEach(record -> memories.add(toMemory(record)));
        episodicStore.listAll().forEach(record -> memories.add(toMemory(record)));
        proceduralStore.listAll().forEach(record -> memories.add(toMemory(record)));
        return memories.stream()
                .sorted(Comparator.comparing(MemoryRecord::updatedAt).reversed())
                .toList();
    }

    @Override
    public MemorySearchResult search(String query) {
        Instant startedAt = Instant.now();
        List<String> tokens = tokens(query);
        List<ScoredMemory> scored = listAll().stream()
                .map(memory -> new ScoredMemory(memory, score(memory, tokens)))
                .filter(memory -> tokens.isEmpty() || memory.score() > 0)
                .sorted(Comparator.comparingInt(ScoredMemory::score).reversed())
                .limit(SEARCH_LIMIT)
                .toList();
        List<MemoryRecord> memories = scored.stream().map(ScoredMemory::memory).toList();
        long durationMs = Duration.between(startedAt, Instant.now()).toMillis();
        LOGGER.info("[JARVIS] Memory search query=\"{}\" executionTimeMs={} results={}",
                query, durationMs, memories.size());
        return new MemorySearchResult(query, durationMs, memories);
    }

    @Override
    public CognitiveMemoryContext retrieveContext(String query) {
        List<MemoryRecord> memories = search(query).memories();
        if (memories.isEmpty()) {
            return CognitiveMemoryContext.empty();
        }
        StringBuilder builder = new StringBuilder();
        builder.append("========================================\n\n");
        builder.append("COGNITIVE MEMORY\n\n");
        for (MemoryRecord memory : memories) {
            String block = """
                    Type: %s
                    Memory: %s
                    %s

                    ----------------------------------------

                    """.formatted(memory.type(), memory.title(), memory.content());
            if (builder.length() + block.length() > CONTEXT_LIMIT) {
                break;
            }
            builder.append(block);
        }
        builder.append("========================================\n\n");
        String context = builder.toString();
        return new CognitiveMemoryContext(
                context,
                memories,
                memories.size(),
                context.length(),
                context.length() / 4,
                false
        );
    }

    @Override
    public List<MemoryMutation> updateFromConversation(String conversationId, String userMessage, String assistantResponse) {
        List<MemoryMutation> mutations = new ArrayList<>();
        for (MemoryCandidate candidate : classifier.classify(userMessage)) {
            switch (candidate.type()) {
                case SEMANTIC -> mutations.add(upsertSemantic(conversationId, candidate));
                case EPISODIC -> mutations.add(createEpisodic(conversationId, candidate));
                case PROCEDURAL -> mutations.add(upsertProcedural(conversationId, candidate));
            }
        }
        if (mutations.isEmpty()) {
            LOGGER.info("[JARVIS] Memory update skipped conversationId={} reason=no deterministic candidates", conversationId);
        }
        return mutations;
    }

    @Override
    public int reindex() {
        int count = listAll().size();
        LOGGER.info("[JARVIS] Memory reindex completed indexedMemories={}", count);
        return count;
    }

    @Override
    public boolean delete(UUID id) {
        return semanticStore.delete(id) || episodicStore.delete(id) || proceduralStore.delete(id);
    }

    private MemoryMutation upsertSemantic(String conversationId, MemoryCandidate candidate) {
        Instant now = Instant.now();
        var exact = semanticStore.findExact(candidate.subject(), candidate.predicate(), candidate.value());
        if (exact.isPresent()) {
            return new MemoryMutation(MemoryMutationType.SKIPPED, toMemory(exact.get()), "repeated fact");
        }
        var conflict = semanticStore.listAll().stream()
                .filter(memory -> memory.subject().equalsIgnoreCase(candidate.subject()))
                .filter(memory -> memory.predicate().equalsIgnoreCase(candidate.predicate()))
                .filter(memory -> candidate.predicate().startsWith("has."))
                .findFirst();
        if (conflict.isPresent()) {
            SemanticMemoryRecord existing = conflict.get();
            SemanticMemoryRecord updated = new SemanticMemoryRecord(
                    existing.id(),
                    existing.subject(),
                    existing.predicate(),
                    candidate.value(),
                    Math.max(existing.confidence(), candidate.confidence()),
                    existing.createdAt(),
                    now,
                    conversationId
            );
            semanticStore.update(updated);
            return new MemoryMutation(MemoryMutationType.UPDATED, toMemory(updated), "updated fact");
        }
        SemanticMemoryRecord created = new SemanticMemoryRecord(
                UUID.randomUUID(),
                candidate.subject(),
                candidate.predicate(),
                candidate.value(),
                candidate.confidence(),
                now,
                now,
                conversationId
        );
        semanticStore.save(created);
        return new MemoryMutation(MemoryMutationType.CREATED, toMemory(created), "new fact");
    }

    private MemoryMutation createEpisodic(String conversationId, MemoryCandidate candidate) {
        Instant now = Instant.now();
        EpisodicMemoryRecord created = new EpisodicMemoryRecord(
                UUID.randomUUID(),
                candidate.predicate(),
                candidate.value(),
                candidate.confidence(),
                now,
                conversationId
        );
        episodicStore.save(created);
        return new MemoryMutation(MemoryMutationType.CREATED, toMemory(created), "new event");
    }

    private MemoryMutation upsertProcedural(String conversationId, MemoryCandidate candidate) {
        Instant now = Instant.now();
        var existing = proceduralStore.findByName(candidate.predicate());
        if (existing.isPresent() && existing.get().steps().equalsIgnoreCase(candidate.value())) {
            return new MemoryMutation(MemoryMutationType.SKIPPED, toMemory(existing.get()), "repeated procedure");
        }
        if (existing.isPresent()) {
            ProceduralMemoryRecord previous = existing.get();
            ProceduralMemoryRecord updated = new ProceduralMemoryRecord(
                    previous.id(),
                    previous.name(),
                    candidate.value(),
                    Math.max(previous.confidence(), candidate.confidence()),
                    previous.createdAt(),
                    now,
                    conversationId
            );
            proceduralStore.update(updated);
            return new MemoryMutation(MemoryMutationType.UPDATED, toMemory(updated), "updated procedure");
        }
        ProceduralMemoryRecord created = new ProceduralMemoryRecord(
                UUID.randomUUID(),
                candidate.predicate(),
                candidate.value(),
                candidate.confidence(),
                now,
                now,
                conversationId
        );
        proceduralStore.save(created);
        return new MemoryMutation(MemoryMutationType.CREATED, toMemory(created), "new procedure");
    }

    private MemoryRecord toMemory(SemanticMemoryRecord record) {
        return new MemoryRecord(
                record.id(),
                MemoryType.SEMANTIC,
                record.subject() + " " + record.predicate(),
                record.subject() + " " + record.predicate() + " " + record.value(),
                record.confidence(),
                record.createdAt(),
                record.updatedAt(),
                record.sourceConversation()
        );
    }

    private MemoryRecord toMemory(EpisodicMemoryRecord record) {
        return new MemoryRecord(
                record.id(),
                MemoryType.EPISODIC,
                record.title(),
                record.description(),
                record.importance(),
                record.createdAt(),
                record.createdAt(),
                record.sourceConversation()
        );
    }

    private MemoryRecord toMemory(ProceduralMemoryRecord record) {
        return new MemoryRecord(
                record.id(),
                MemoryType.PROCEDURAL,
                record.name(),
                record.steps(),
                record.confidence(),
                record.createdAt(),
                record.updatedAt(),
                record.sourceConversation()
        );
    }

    private List<String> tokens(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String token : query.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (!token.isBlank()) {
                unique.add(token);
            }
        }
        return List.copyOf(unique);
    }

    private int score(MemoryRecord memory, List<String> tokens) {
        if (tokens.isEmpty()) {
            return 1;
        }
        String title = memory.title().toLowerCase(Locale.ROOT);
        String content = memory.content().toLowerCase(Locale.ROOT);
        int score = 0;
        for (String token : tokens) {
            if (title.contains(token)) {
                score += 50;
            }
            if (content.contains(token)) {
                score += 20;
            }
        }
        return score;
    }

    private record ScoredMemory(MemoryRecord memory, int score) {
    }
}
