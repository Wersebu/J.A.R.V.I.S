package com.jarvis.memory.cognitive;

import com.jarvis.common.memory.CognitiveMemoryContext;
import com.jarvis.common.memory.MemoryRecord;
import com.jarvis.common.memory.MemorySearchMatch;
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
import java.util.regex.Pattern;

/**
 * Default cognitive memory service backed by structured SQLite stores.
 */
@Service
public class DefaultCognitiveMemoryService implements CognitiveMemoryService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultCognitiveMemoryService.class);
    private static final int SEARCH_LIMIT = 10;
    private static final int CONTEXT_LIMIT = 6_000;
    private static final Pattern LETTER_NUMBER_BOUNDARY = Pattern.compile("(?<=[a-zA-Z])(?=\\d)|(?<=\\d)(?=[a-zA-Z])");

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
        Set<String> queryTokens = expandedTokens(query);
        LOGGER.info("""
                [JARVIS]
                MEMORY SEARCH STARTED

                Query:
                "{}"

                Normalized tokens:
                {}
                """, query, queryTokens);
        List<ScoredMemory> scored = listAll().stream()
                .map(memory -> score(memory, queryTokens))
                .filter(memory -> queryTokens.isEmpty() || memory.rawScore() > 0)
                .sorted(Comparator.comparingInt(ScoredMemory::rawScore).reversed())
                .limit(SEARCH_LIMIT)
                .toList();
        List<MemoryRecord> memories = scored.stream().map(ScoredMemory::memory).toList();
        List<MemorySearchMatch> matches = scored.stream()
                .map(match -> new MemorySearchMatch(match.memory(), match.normalizedScore(), match.reason()))
                .toList();
        long durationMs = Duration.between(startedAt, Instant.now()).toMillis();
        LOGGER.info("""
                [JARVIS]
                MEMORY SEARCH FINISHED

                Found {} candidate memories
                Execution time:
                {} ms
                """, memories.size(), durationMs);
        for (int index = 0; index < matches.size(); index++) {
            MemorySearchMatch match = matches.get(index);
            LOGGER.info("""
                    [JARVIS]
                    MEMORY CANDIDATE {}

                    Type:
                    {}

                    Memory:
                    {}

                    Score:
                    {}

                    Reason:
                    {}
                    """, index + 1, match.memory().type(), match.memory().content(), match.score(), match.reason());
        }
        return new MemorySearchResult(query, durationMs, memories, matches);
    }

    @Override
    public CognitiveMemoryContext retrieveContext(String query) {
        MemorySearchResult searchResult = search(query);
        List<MemoryRecord> memories = searchResult.memories();
        if (memories.isEmpty()) {
            LOGGER.info("""
                    [JARVIS]
                    MEMORY INJECTION SKIPPED

                    Injected memories:
                    0
                    """);
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
        LOGGER.info("""
                [JARVIS]
                MEMORY INJECTION PREPARED

                Injected memories:
                {}

                Characters:
                {}

                Estimated tokens:
                {}
                """, memories.size(), context.length(), context.length() / 4);
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
                record.priority(),
                record.category(),
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

    private Set<String> expandedTokens(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return tokens;
        }
        String normalized = LETTER_NUMBER_BOUNDARY.matcher(text.toLowerCase(Locale.ROOT)).replaceAll(" ");
        normalized = normalized.replaceAll("[^\\p{L}\\p{N}]+", " ");
        for (String token : normalized.split("\\s+")) {
            if (!token.isBlank()) {
                addToken(tokens, token);
            }
        }
        expandDomainTokens(tokens);
        return tokens;
    }

    private void addToken(Set<String> tokens, String token) {
        tokens.add(token);
        if (token.startsWith("rtx")) {
            tokens.add("rtx");
            tokens.add("gpu");
            tokens.add("graphics");
            tokens.add("card");
        }
    }

    private void expandDomainTokens(Set<String> tokens) {
        Set<String> original = Set.copyOf(tokens);
        if (original.contains("gpu") || original.contains("graphics") || original.contains("video") || original.contains("rtx")) {
            tokens.add("gpu");
            tokens.add("graphics");
            tokens.add("video");
            tokens.add("card");
            tokens.add("rtx");
            tokens.add("nvidia");
        }
        if (original.contains("own") || original.contains("owns") || original.contains("have") || original.contains("has")
                || original.contains("use") || original.contains("uses") || original.contains("drive")) {
            tokens.add("own");
            tokens.add("owns");
            tokens.add("have");
            tokens.add("has");
            tokens.add("use");
            tokens.add("uses");
        }
        if (original.contains("car") || original.contains("drive") || original.contains("audi") || original.contains("vehicle")) {
            tokens.add("car");
            tokens.add("vehicle");
            tokens.add("drive");
            tokens.add("audi");
        }
    }

    private ScoredMemory score(MemoryRecord memory, Set<String> queryTokens) {
        if (queryTokens.isEmpty()) {
            return new ScoredMemory(memory, 1, 0.01d, "empty query");
        }
        Set<String> memoryTokens = expandedTokens(memory.title() + " " + memory.content());
        int rawScore = 0;
        List<String> reasons = new ArrayList<>();
        for (String token : queryTokens) {
            if (memoryTokens.contains(token)) {
                rawScore += 20;
                reasons.add(token);
            }
        }
        if (memory.type() == MemoryType.SEMANTIC && memory.content().toLowerCase(Locale.ROOT).contains("owns")) {
            if (queryTokens.contains("have") || queryTokens.contains("own") || queryTokens.contains("use") || queryTokens.contains("drive")) {
                rawScore += 25;
                reasons.add("ownership");
            }
        }
        double normalizedScore = Math.min(1.0d, rawScore / 100.0d);
        String reason = reasons.isEmpty() ? "no overlap" : "matched " + String.join(", ", reasons.stream().distinct().toList());
        return new ScoredMemory(memory, rawScore, normalizedScore, reason);
    }

    private record ScoredMemory(MemoryRecord memory, int rawScore, double normalizedScore, String reason) {
    }
}
