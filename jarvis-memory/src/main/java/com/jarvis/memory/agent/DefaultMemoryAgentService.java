package com.jarvis.memory.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.common.ai.AIJobType;
import com.jarvis.common.ai.AIProvider;
import com.jarvis.common.ai.Brain;
import com.jarvis.common.ai.BrainType;
import com.jarvis.common.ai.ReasoningLevel;
import com.jarvis.common.event.CognitiveEventBus;
import com.jarvis.common.event.CognitiveEventType;
import com.jarvis.common.memory.MemoryCategory;
import com.jarvis.common.memory.MemoryPriority;
import com.jarvis.memory.cognitive.SemanticMemoryRecord;
import com.jarvis.memory.cognitive.SemanticMemoryStore;
import com.jarvis.memory.embedding.EmbeddingMemoryEngine;
import com.jarvis.memory.job.MemoryJob;
import com.jarvis.memory.retrieval.MemoryQueryNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * AI-powered memory agent executed by the background memory job queue.
 */
@Service
public class DefaultMemoryAgentService implements MemoryAgentService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultMemoryAgentService.class);
    private static final String MODEL = "gpt-oss:20b";

    private final List<AIProvider> aiProviders;
    private final SemanticMemoryStore semanticStore;
    private final ObjectMapper objectMapper;
    private final MemoryQueryNormalizer queryNormalizer;
    private final EmbeddingMemoryEngine embeddingMemoryEngine;
    private final CognitiveEventBus cognitiveEventBus;

    /**
     * Creates the default memory agent service.
     *
     * @param aiProviders available AI providers
     * @param semanticStore semantic memory store
     * @param objectMapper JSON object mapper
     * @param queryNormalizer memory query normalizer
     * @param embeddingMemoryEngine embedding memory engine
     * @param cognitiveEventBus cognitive event bus
     */
    public DefaultMemoryAgentService(
            List<AIProvider> aiProviders,
            SemanticMemoryStore semanticStore,
            ObjectMapper objectMapper,
            MemoryQueryNormalizer queryNormalizer,
            EmbeddingMemoryEngine embeddingMemoryEngine,
            CognitiveEventBus cognitiveEventBus
    ) {
        this.aiProviders = List.copyOf(aiProviders);
        this.semanticStore = semanticStore;
        this.objectMapper = objectMapper;
        this.queryNormalizer = queryNormalizer;
        this.embeddingMemoryEngine = embeddingMemoryEngine;
        this.cognitiveEventBus = cognitiveEventBus;
    }

    @Override
    public void analyze(MemoryJob job) {
        Instant startedAt = Instant.now();
        publish(job, CognitiveEventType.MEMORY_AGENT_STARTED, "STARTED",
                "Memory Agent analyzing conversation", "memory:agent", Map.of(
                        "existingMemories", job.currentMemories().size()
                ));
        LOGGER.info("[JARVIS][MEMORY][jobId={}][sourceRequestId={}] MEMORY_AGENT_STARTED",
                job.memoryJobId(), job.sourceRequestId());

        MemoryAgentDecision decision = decide(job);
        publish(job, CognitiveEventType.MEMORY_AGENT_DECISION, decision.action().name(),
                "Memory Agent decision", "memory:agent", Map.of(
                        "action", decision.action().name(),
                        "category", safeCategory(decision).name(),
                        "priority", safePriority(decision).name(),
                        "confidence", decision.confidence(),
                        "reason", decision.reason() == null ? "" : decision.reason()
                ));

        applyDecision(job, decision);
        long durationMs = java.time.Duration.between(startedAt, Instant.now()).toMillis();
        publish(job, CognitiveEventType.MEMORY_AGENT_FINISHED, "FINISHED",
                "Memory Agent finished", "memory:agent", Map.of("durationMs", durationMs));
        LOGGER.info("[JARVIS][MEMORY][jobId={}][sourceRequestId={}] MEMORY_AGENT_FINISHED action={} durationMs={}",
                job.memoryJobId(), job.sourceRequestId(), decision.action(), durationMs);
    }

    private MemoryAgentDecision decide(MemoryJob job) {
        try {
            String prompt = prompt(job);
            Brain brain = new Brain(BrainType.CLASSIFIER, "ollama", MODEL, "Background memory agent")
                    .withRoutingMetadata("Memory extraction", 0L, ReasoningLevel.LOW);
            String response = selectProvider().chat(brain, prompt, AIJobType.MEMORY_AGENT).response();
            return parseDecision(response);
        } catch (RuntimeException exception) {
            LOGGER.error("[JARVIS][MEMORY][jobId={}][sourceRequestId={}] MEMORY_AGENT_DECISION_FAILED",
                    job.memoryJobId(), job.sourceRequestId(), exception);
            publish(job, CognitiveEventType.MEMORY_AGENT_ERROR, "ERROR",
                    "Memory Agent decision failed", "memory:agent", Map.of(
                            "error", exception.getMessage() == null ? "" : exception.getMessage()
                    ));
            return MemoryAgentDecision.none("agent failure");
        }
    }

    private AIProvider selectProvider() {
        return aiProviders.stream()
                .filter(provider -> provider.provider().equalsIgnoreCase("ollama"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Ollama provider is not available for Memory Agent"));
    }

    private MemoryAgentDecision parseDecision(String response) {
        try {
            String json = extractJson(response);
            MemoryAgentDecision decision = objectMapper.readValue(json, MemoryAgentDecision.class);
            if (decision.action() == null) {
                return MemoryAgentDecision.none("missing action");
            }
            return decision;
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            LOGGER.warn("[JARVIS] Memory Agent returned invalid JSON: {}", response);
            return MemoryAgentDecision.none("missing action");
        }
    }

    private String extractJson(String response) {
        String value = response == null ? "" : response.strip();
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return value.substring(start, end + 1);
        }
        return "{\"action\":\"NONE\",\"reason\":\"no json returned\"}";
    }

    private void applyDecision(MemoryJob job, MemoryAgentDecision decision) {
        switch (decision.action()) {
            case NONE -> publish(job, CognitiveEventType.MEMORY_SKIPPED, "SKIPPED",
                    "Memory Agent skipped update", "memory:agent", Map.of("reason", decision.reason() == null ? "" : decision.reason()));
            case CREATE -> createMemory(job, decision);
            case UPDATE -> updateMemory(job, decision);
            case DELETE -> deleteMemory(job, decision);
        }
    }

    private void createMemory(MemoryJob job, MemoryAgentDecision decision) {
        Instant now = Instant.now();
        String content = usefulContent(decision);
        SemanticMemoryRecord existing = findRelatedMemory(content, safeCategory(decision));
        if (existing != null) {
            if (specificity(content) <= specificity(existing.value())) {
                publish(job, CognitiveEventType.MEMORY_SKIPPED, "SKIPPED",
                        "Memory Agent skipped less specific duplicate", "memory:" + existing.id(), Map.of(
                                "existingContent", existing.value(),
                                "candidateContent", content
                        ));
                return;
            }
            updateExistingMemory(job, decision, existing, content);
            return;
        }
        SemanticMemoryRecord record = new SemanticMemoryRecord(
                UUID.randomUUID(),
                "user",
                "remembers",
                content,
                confidence(decision),
                safePriority(decision),
                safeCategory(decision),
                now,
                now,
                job.conversationId()
        );
        semanticStore.save(record);
        indexEmbedding(job, record);
        publish(job, CognitiveEventType.MEMORY_CREATED, "CREATED", "Memory created",
                "memory-record:" + record.id(), Map.of("title", memoryTitle(content), "content", content, "category", record.category().name(), "priority", record.priority().name()));
    }

    private void updateMemory(MemoryJob job, MemoryAgentDecision decision) {
        SemanticMemoryRecord existing = findMemoryForUpdate(decision);
        if (existing == null) {
            createMemory(job, decision);
            return;
        }
        updateExistingMemory(job, decision, existing, usefulContent(decision));
    }

    private void updateExistingMemory(
            MemoryJob job,
            MemoryAgentDecision decision,
            SemanticMemoryRecord existing,
            String content
    ) {
        Instant now = Instant.now();
        SemanticMemoryRecord updated = new SemanticMemoryRecord(
                existing.id(),
                existing.subject(),
                existing.predicate(),
                content,
                confidence(decision),
                safePriority(decision),
                safeCategory(decision),
                existing.createdAt(),
                now,
                job.conversationId()
        );
        semanticStore.update(updated);
        indexEmbedding(job, updated);
        publish(job, CognitiveEventType.MEMORY_UPDATED, "UPDATED", "Memory updated",
                "memory-record:" + updated.id(), Map.of("title", memoryTitle(content), "oldContent", existing.value(), "newContent", content, "category", updated.category().name()));
    }

    private void deleteMemory(MemoryJob job, MemoryAgentDecision decision) {
        if (decision.memoryId() != null && semanticStore.delete(decision.memoryId())) {
            publish(job, CognitiveEventType.MEMORY_DELETED, "DELETED", "Memory deleted",
                    "memory-record:" + decision.memoryId(), Map.of());
            return;
        }
        publish(job, CognitiveEventType.MEMORY_SKIPPED, "SKIPPED",
                "Memory delete skipped", "memory:agent", Map.of("reason", "memory not found"));
    }

    private String memoryTitle(String content) {
        if (content == null || content.isBlank()) {
            return "Memory";
        }
        String normalized = content.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 80 ? normalized : normalized.substring(0, 80);
    }

    private SemanticMemoryRecord findMemoryForUpdate(MemoryAgentDecision decision) {
        if (decision.memoryId() != null) {
            return semanticStore.findById(decision.memoryId()).orElse(null);
        }
        String oldContent = decision.oldContent() == null ? "" : decision.oldContent().toLowerCase(Locale.ROOT);
        if (oldContent.isBlank()) {
            return null;
        }
        return semanticStore.listAll().stream()
                .filter(memory -> memory.value().toLowerCase(Locale.ROOT).contains(oldContent)
                        || oldContent.contains(memory.value().toLowerCase(Locale.ROOT)))
                .findFirst()
                .orElse(null);
    }

    private SemanticMemoryRecord findRelatedMemory(String content, MemoryCategory category) {
        return semanticStore.listAll().stream()
                .filter(memory -> memory.category() == category)
                .filter(memory -> related(memory.value(), content))
                .findFirst()
                .orElse(null);
    }

    private boolean related(String existing, String candidate) {
        var existingTokens = new java.util.LinkedHashSet<>(queryNormalizer.normalize(existing).tokens());
        var candidateTokens = new java.util.LinkedHashSet<>(queryNormalizer.normalize(candidate).tokens());
        if (existingTokens.isEmpty() || candidateTokens.isEmpty()) {
            return false;
        }
        var intersection = new java.util.LinkedHashSet<>(existingTokens);
        intersection.retainAll(candidateTokens);
        double ratio = intersection.size() / (double) Math.min(existingTokens.size(), candidateTokens.size());
        return ratio >= 0.45d;
    }

    private int specificity(String value) {
        int score = queryNormalizer.normalize(value).tokens().size();
        if (value != null && value.matches(".*\\d+.*")) {
            score += 3;
        }
        return score;
    }

    private String usefulContent(MemoryAgentDecision decision) {
        String value = decision.newContent() == null || decision.newContent().isBlank()
                ? decision.content()
                : decision.newContent();
        return value == null ? "" : value.strip();
    }

    private double confidence(MemoryAgentDecision decision) {
        return decision.confidence() <= 0.0d ? 0.8d : Math.min(1.0d, decision.confidence());
    }

    private MemoryPriority safePriority(MemoryAgentDecision decision) {
        return decision.priority() == null ? MemoryPriority.NORMAL : decision.priority();
    }

    private MemoryCategory safeCategory(MemoryAgentDecision decision) {
        return decision.category() == null ? MemoryCategory.SEMANTIC : decision.category();
    }

    private String prompt(MemoryJob job) {
        String memories = semanticStore.listAll().stream()
                .map(memory -> "- id=%s category=%s priority=%s content=%s".formatted(
                        memory.id(), memory.category(), memory.priority(), memory.value()))
                .reduce("", (left, right) -> left + right + "\n");
        return """
                You are J.A.R.V.I.S. Memory Agent.
                Analyze whether the latest conversation contains durable user memory.
                Return JSON only. No markdown. No explanation outside JSON.

                Supported actions: NONE, CREATE, UPDATE, DELETE.
                Supported priorities: CRITICAL, HIGH, NORMAL, LOW, TEMPORARY.
                Supported categories: SEMANTIC, PREFERENCE, PROJECT, PERSON, RELATIONSHIP, DEVICE, VEHICLE, WORK, PROGRAMMING, LOCATION, TEMPORARY.

                Rules:
                - Remember durable facts, preferences, projects, devices, vehicles, work, relationships, and programming preferences.
                - Ignore temporary events such as meals, casual small talk, and one-off trivia.
                - Prefer specific memories: "User owns RTX3060" is better than "User owns graphics card".
                - If the new fact replaces an old memory, use UPDATE and include memoryId when possible.
                - If the user says they no longer use something, update or delete the old memory.
                - Avoid duplicates.

                JSON shape:
                {
                  "action": "NONE|CREATE|UPDATE|DELETE",
                  "memoryId": null,
                  "content": "",
                  "oldContent": "",
                  "newContent": "",
                  "priority": "NORMAL",
                  "category": "SEMANTIC",
                  "confidence": 0.0,
                  "reason": ""
                }

                Current memories:
                %s

                Latest conversation:
                USER: %s
                ASSISTANT: %s
                """.formatted(memories.isBlank() ? "None" : memories, job.userMessage(), job.assistantAnswer());
    }

    private void indexEmbedding(MemoryJob job, SemanticMemoryRecord record) {
        try {
            embeddingMemoryEngine.index(record);
        } catch (RuntimeException exception) {
            LOGGER.warn("[JARVIS][MEMORY][jobId={}][sourceRequestId={}] EMBEDDING_INDEX_FAILED memoryId={}",
                    job.memoryJobId(), job.sourceRequestId(), record.id(), exception);
        }
    }

    private void publish(
            MemoryJob job,
            CognitiveEventType eventType,
            String status,
            String message,
            String nodeId,
            Map<String, Object> metadata
    ) {
        Map<String, Object> enriched = new LinkedHashMap<>(metadata == null ? Map.of() : metadata);
        enriched.put("memoryJobId", job.memoryJobId().toString());
        enriched.put("sourceRequestId", job.sourceRequestId());
        enriched.put("conversationId", job.conversationId());
        enriched.put("timestamp", Instant.now().toString());
        cognitiveEventBus.publishBackground(
                job.sourceRequestId(),
                job.conversationId(),
                eventType,
                status,
                message,
                nodeId,
                Map.copyOf(enriched)
        );
    }
}
