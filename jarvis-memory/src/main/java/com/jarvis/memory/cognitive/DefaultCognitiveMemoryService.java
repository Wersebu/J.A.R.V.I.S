package com.jarvis.memory.cognitive;

import com.jarvis.common.memory.CognitiveMemoryContext;
import com.jarvis.common.memory.MemoryRecord;
import com.jarvis.common.memory.MemoryCategory;
import com.jarvis.common.memory.MemorySearchMatch;
import com.jarvis.common.memory.MemorySearchResult;
import com.jarvis.common.memory.MemoryType;
import com.jarvis.memory.retrieval.MemoryCandidateRetriever;
import com.jarvis.memory.retrieval.MemoryProfileBuilder;
import com.jarvis.memory.retrieval.MemoryQuery;
import com.jarvis.memory.retrieval.MemoryQueryNormalizer;
import com.jarvis.memory.retrieval.MemoryReranker;
import com.jarvis.memory.retrieval.MemoryScore;
import com.jarvis.memory.retrieval.MemoryScorer;
import com.jarvis.memory.retrieval.DefaultMemoryQueryNormalizer;
import com.jarvis.memory.retrieval.IndexedMemoryCandidateRetriever;
import com.jarvis.memory.retrieval.NoOpMemoryReranker;
import com.jarvis.memory.retrieval.StructuredMemoryProfileBuilder;
import com.jarvis.memory.retrieval.TokenMemoryScorer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
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
    private final MemoryQueryNormalizer queryNormalizer;
    private final MemoryCandidateRetriever candidateRetriever;
    private final MemoryScorer memoryScorer;
    private final MemoryReranker memoryReranker;
    private final MemoryProfileBuilder profileBuilder;

    /**
     * Creates the default cognitive memory service.
     *
     * @param semanticStore semantic memory store
     * @param episodicStore episodic memory store
     * @param proceduralStore procedural memory store
     * @param classifier deterministic classifier
     */
    @Autowired
    public DefaultCognitiveMemoryService(
            SemanticMemoryStore semanticStore,
            EpisodicMemoryStore episodicStore,
            ProceduralMemoryStore proceduralStore,
            DeterministicMemoryClassifier classifier,
            MemoryQueryNormalizer queryNormalizer,
            MemoryCandidateRetriever candidateRetriever,
            MemoryScorer memoryScorer,
            MemoryReranker memoryReranker,
            MemoryProfileBuilder profileBuilder
    ) {
        this.semanticStore = semanticStore;
        this.episodicStore = episodicStore;
        this.proceduralStore = proceduralStore;
        this.classifier = classifier;
        this.queryNormalizer = queryNormalizer;
        this.candidateRetriever = candidateRetriever;
        this.memoryScorer = memoryScorer;
        this.memoryReranker = memoryReranker;
        this.profileBuilder = profileBuilder;
    }

    /**
     * Creates the service with default retrieval components.
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
        this(
                semanticStore,
                episodicStore,
                proceduralStore,
                classifier,
                new DefaultMemoryQueryNormalizer(),
                new IndexedMemoryCandidateRetriever(new DefaultMemoryQueryNormalizer()),
                new TokenMemoryScorer(new DefaultMemoryQueryNormalizer()),
                new NoOpMemoryReranker(),
                new StructuredMemoryProfileBuilder()
        );
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
        MemoryQuery normalizedQuery = queryNormalizer.normalize(query);
        LOGGER.info("""
                [JARVIS]
                MEMORY SEARCH STARTED

                Original query:
                "{}"

                Normalized query:
                {}
                """, query, normalizedQuery.tokens());
        List<MemoryRecord> candidates = candidateRetriever.candidates(normalizedQuery, listAll());
        List<MemoryScore> scored = candidates.stream()
                .map(memory -> memoryScorer.score(normalizedQuery, memory))
                .filter(memory -> memory.score() >= 0.18d)
                .sorted(Comparator.comparingDouble(MemoryScore::score).reversed())
                .limit(SEARCH_LIMIT)
                .toList();
        scored = applyReranking(query, scored);
        List<MemoryRecord> memories = scored.stream().map(MemoryScore::memory).toList();
        List<MemorySearchMatch> matches = scored.stream()
                .map(match -> new MemorySearchMatch(match.memory(), match.score(), match.reason()))
                .toList();
        long durationMs = Duration.between(startedAt, Instant.now()).toMillis();
        LOGGER.info("""
                [JARVIS]
                MEMORY SEARCH FINISHED

                Candidates:
                {}

                Scoring:
                {}

                Best candidate:
                {}

                Memory retrieval:
                {} ms
                """,
                candidates.size(),
                scoreLog(matches),
                matches.isEmpty() ? "None" : matches.getFirst().memory().content(),
                durationMs);
        return new MemorySearchResult(query, durationMs, memories, matches, normalizedQuery.tokens(), candidates.size());
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
        String context = profileBuilder.buildProfile(memories);
        if (context.length() > CONTEXT_LIMIT) {
            context = context.substring(0, CONTEXT_LIMIT);
        }
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

    private List<MemoryScore> applyReranking(String query, List<MemoryScore> scored) {
        if (scored.size() < 2) {
            return scored;
        }
        List<MemoryScore> sensible = scored.stream()
                .filter(score -> score.score() >= 0.28d)
                .limit(5)
                .toList();
        if (sensible.size() < 2) {
            return scored;
        }
        return memoryReranker.rerank(query, sensible)
                .map(selectedId -> scored.stream()
                        .sorted((left, right) -> {
                            if (left.memory().id().equals(selectedId)) {
                                return -1;
                            }
                            if (right.memory().id().equals(selectedId)) {
                                return 1;
                            }
                            return Double.compare(right.score(), left.score());
                        })
                        .toList())
                .orElse(scored);
    }

    private String scoreLog(List<MemorySearchMatch> matches) {
        if (matches.isEmpty()) {
            return "None";
        }
        StringBuilder builder = new StringBuilder();
        for (MemorySearchMatch match : matches) {
            builder.append(match.memory().content())
                    .append(" -> ")
                    .append("%.2f".formatted(match.score()))
                    .append('\n');
        }
        return builder.toString();
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
        MemoryCategory category = inferCategory(candidate);
        var exact = semanticStore.findExact(candidate.subject(), candidate.predicate(), candidate.value());
        if (exact.isPresent()) {
            return new MemoryMutation(MemoryMutationType.SKIPPED, toMemory(exact.get()), "repeated fact");
        }
        var duplicate = semanticStore.listAll().stream()
                .filter(memory -> memory.category() == category)
                .filter(memory -> related(memory.value(), candidate.value()))
                .findFirst();
        if (duplicate.isPresent()) {
            SemanticMemoryRecord existing = duplicate.get();
            if (specificity(candidate.value()) <= specificity(existing.value())) {
                return new MemoryMutation(MemoryMutationType.SKIPPED, toMemory(existing), "less specific duplicate");
            }
            SemanticMemoryRecord updated = new SemanticMemoryRecord(
                    existing.id(),
                    candidate.subject(),
                    candidate.predicate(),
                    candidate.value(),
                    Math.max(existing.confidence(), candidate.confidence()),
                    existing.priority(),
                    category,
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
                com.jarvis.common.memory.MemoryPriority.NORMAL,
                category,
                now,
                now,
                conversationId
        );
        semanticStore.save(created);
        return new MemoryMutation(MemoryMutationType.CREATED, toMemory(created), "new fact");
    }

    private MemoryCategory inferCategory(MemoryCandidate candidate) {
        MemoryQuery query = queryNormalizer.normalize(candidate.predicate() + " " + candidate.value());
        return query.preferredCategories().stream().findFirst().orElse(MemoryCategory.SEMANTIC);
    }

    private boolean related(String existing, String candidate) {
        Set<String> existingTokens = Set.copyOf(queryNormalizer.normalize(existing).tokens());
        Set<String> candidateTokens = Set.copyOf(queryNormalizer.normalize(candidate).tokens());
        if (existingTokens.isEmpty() || candidateTokens.isEmpty()) {
            return false;
        }
        Set<String> intersection = new LinkedHashSet<>(existingTokens);
        intersection.retainAll(candidateTokens);
        double ratio = intersection.size() / (double) Math.min(existingTokens.size(), candidateTokens.size());
        return ratio >= 0.45d;
    }

    private int specificity(String value) {
        MemoryQuery query = queryNormalizer.normalize(value);
        int score = query.tokens().size();
        if (value != null && value.matches(".*\\d+.*")) {
            score += 3;
        }
        return score;
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
}
