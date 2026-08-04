package com.jarvis.memory.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.common.ai.AIProvider;
import com.jarvis.common.ai.Brain;
import com.jarvis.common.ai.BrainType;
import com.jarvis.common.ai.ReasoningLevel;
import com.jarvis.common.event.CognitiveEvent;
import com.jarvis.common.event.CognitiveEventType;
import com.jarvis.common.memory.MemoryCategory;
import com.jarvis.common.memory.MemoryPriority;
import com.jarvis.common.memory.MemoryRecord;
import com.jarvis.memory.cognitive.SemanticMemoryRecord;
import com.jarvis.memory.cognitive.SemanticMemoryStore;
import com.jarvis.memory.pipeline.PipelineContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Default AI-powered asynchronous memory agent.
 */
@Service
public class DefaultMemoryAgentService implements MemoryAgentService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultMemoryAgentService.class);
    private static final String MODEL = "gpt-oss:20b";

    private final List<AIProvider> aiProviders;
    private final SemanticMemoryStore semanticStore;
    private final ObjectMapper objectMapper;
    private final ExecutorService executorService;

    /**
     * Creates the default memory agent service.
     *
     * @param aiProviders available AI providers
     * @param semanticStore semantic memory store
     * @param objectMapper JSON object mapper
     */
    public DefaultMemoryAgentService(
            List<AIProvider> aiProviders,
            SemanticMemoryStore semanticStore,
            ObjectMapper objectMapper
    ) {
        this.aiProviders = List.copyOf(aiProviders);
        this.semanticStore = semanticStore;
        this.objectMapper = objectMapper;
        this.executorService = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "jarvis-memory-agent");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public CompletableFuture<Void> analyzeAsync(PipelineContext context, Consumer<CognitiveEvent> eventSink) {
        return CompletableFuture.runAsync(() -> analyze(context, eventSink == null ? event -> { } : eventSink), executorService)
                .exceptionally(exception -> {
                    LOGGER.error("[JARVIS] Memory Agent failed", exception);
                    publish(context, eventSink, CognitiveEventType.MEMORY_AGENT_FINISHED, "FAILED",
                            "Memory Agent failed", null, Map.of("error", exception.getMessage() == null ? "" : exception.getMessage()));
                    return null;
                });
    }

    private void analyze(PipelineContext context, Consumer<CognitiveEvent> eventSink) {
        Instant startedAt = Instant.now();
        publish(context, eventSink, CognitiveEventType.MEMORY_AGENT_STARTED, "STARTED",
                "Memory Agent analyzing conversation", "memory:agent", Map.of(
                        "conversationId", context.conversationId(),
                        "existingMemories", context.memoryContext().memoryCount()
                ));
        LOGGER.info("[JARVIS] Memory Agent started conversationId={}", context.conversationId());

        MemoryAgentDecision decision = decide(context);
        publish(context, eventSink, CognitiveEventType.MEMORY_AGENT_DECISION, decision.action().name(),
                "Memory Agent decision", "memory:agent", Map.of(
                        "action", decision.action().name(),
                        "category", safeCategory(decision).name(),
                        "priority", safePriority(decision).name(),
                        "confidence", decision.confidence(),
                        "reason", decision.reason() == null ? "" : decision.reason()
                ));

        applyDecision(context, eventSink, decision);
        long durationMs = java.time.Duration.between(startedAt, Instant.now()).toMillis();
        publish(context, eventSink, CognitiveEventType.MEMORY_AGENT_FINISHED, "FINISHED",
                "Memory Agent finished", "memory:agent", Map.of("durationMs", durationMs));
        LOGGER.info("[JARVIS] Memory Agent finished action={} durationMs={}", decision.action(), durationMs);
    }

    private MemoryAgentDecision decide(PipelineContext context) {
        try {
            String prompt = prompt(context);
            Brain brain = new Brain(BrainType.CLASSIFIER, "ollama", MODEL, "Background memory agent")
                    .withRoutingMetadata("Memory extraction", 0L, ReasoningLevel.LOW);
            String response = selectProvider().chat(brain, prompt).response();
            return parseDecision(response);
        } catch (RuntimeException exception) {
            LOGGER.error("[JARVIS] Memory Agent decision failed", exception);
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

    private void applyDecision(PipelineContext context, Consumer<CognitiveEvent> eventSink, MemoryAgentDecision decision) {
        switch (decision.action()) {
            case NONE -> publish(context, eventSink, CognitiveEventType.MEMORY_SKIPPED, "SKIPPED",
                    "Memory Agent skipped update", "memory:agent", Map.of("reason", decision.reason() == null ? "" : decision.reason()));
            case CREATE -> createMemory(context, eventSink, decision);
            case UPDATE -> updateMemory(context, eventSink, decision);
            case DELETE -> deleteMemory(context, eventSink, decision);
        }
    }

    private void createMemory(PipelineContext context, Consumer<CognitiveEvent> eventSink, MemoryAgentDecision decision) {
        Instant now = Instant.now();
        String content = usefulContent(decision);
        SemanticMemoryRecord existing = findRelatedMemory(content, safeCategory(decision));
        if (existing != null) {
            updateExistingMemory(context, eventSink, decision, existing, content);
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
                context.conversationId()
        );
        semanticStore.save(record);
        publish(context, eventSink, CognitiveEventType.MEMORY_CREATED, "CREATED", "Memory created",
                "memory:" + record.id(), Map.of("content", content, "category", record.category().name(), "priority", record.priority().name()));
    }

    private void updateMemory(PipelineContext context, Consumer<CognitiveEvent> eventSink, MemoryAgentDecision decision) {
        SemanticMemoryRecord existing = findMemoryForUpdate(decision);
        if (existing == null) {
            createMemory(context, eventSink, decision);
            return;
        }
        String content = usefulContent(decision);
        updateExistingMemory(context, eventSink, decision, existing, content);
    }

    private void updateExistingMemory(
            PipelineContext context,
            Consumer<CognitiveEvent> eventSink,
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
                context.conversationId()
        );
        semanticStore.update(updated);
        publish(context, eventSink, CognitiveEventType.MEMORY_UPDATED, "UPDATED", "Memory updated",
                "memory:" + updated.id(), Map.of("oldContent", existing.value(), "newContent", content));
    }

    private void deleteMemory(PipelineContext context, Consumer<CognitiveEvent> eventSink, MemoryAgentDecision decision) {
        if (decision.memoryId() != null && semanticStore.delete(decision.memoryId())) {
            publish(context, eventSink, CognitiveEventType.MEMORY_DELETED, "DELETED", "Memory deleted",
                    "memory:" + decision.memoryId(), Map.of());
            return;
        }
        publish(context, eventSink, CognitiveEventType.MEMORY_SKIPPED, "SKIPPED",
                "Memory delete skipped", "memory:agent", Map.of("reason", "memory not found"));
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
        String normalized = content == null ? "" : content.toLowerCase(Locale.ROOT);
        return semanticStore.listAll().stream()
                .filter(memory -> memory.category() == category)
                .filter(memory -> isSameMemoryDomain(memory.value().toLowerCase(Locale.ROOT), normalized))
                .findFirst()
                .orElse(null);
    }

    private boolean isSameMemoryDomain(String existing, String candidate) {
        if (existing.equalsIgnoreCase(candidate)) {
            return true;
        }
        return hasAny(existing, "rtx", "gpu", "graphics card", "video card")
                && hasAny(candidate, "rtx", "gpu", "graphics card", "video card")
                || hasAny(existing, "audi", "car", "vehicle")
                && hasAny(candidate, "audi", "car", "vehicle")
                || hasAny(existing, "ide", "intellij", "eclipse")
                && hasAny(candidate, "ide", "intellij", "eclipse");
    }

    private boolean hasAny(String value, String... tokens) {
        for (String token : tokens) {
            if (value.contains(token)) {
                return true;
            }
        }
        return false;
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

    private String prompt(PipelineContext context) {
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
                Supported categories: SEMANTIC, PREFERENCE, PROJECT, RELATIONSHIP, DEVICE, VEHICLE, WORK, PROGRAMMING, TEMPORARY.

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
                """.formatted(memories.isBlank() ? "None" : memories, context.request().message(), context.response());
    }

    private void publish(
            PipelineContext context,
            Consumer<CognitiveEvent> eventSink,
            CognitiveEventType eventType,
            String status,
            String message,
            String nodeId,
            Map<String, Object> metadata
    ) {
        eventSink.accept(new CognitiveEvent(
                context.requestId(),
                context.conversationId(),
                Instant.now(),
                eventType,
                status,
                message,
                BrainType.CLASSIFIER,
                MODEL,
                nodeId,
                metadata
        ));
    }
}
