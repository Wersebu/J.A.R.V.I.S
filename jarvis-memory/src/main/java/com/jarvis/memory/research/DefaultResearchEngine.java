package com.jarvis.memory.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.common.ai.AIJobType;
import com.jarvis.common.ai.AIProvider;
import com.jarvis.common.ai.AIProviderException;
import com.jarvis.common.dto.ChatResponse;
import com.jarvis.common.event.CognitiveEventBus;
import com.jarvis.common.event.CognitiveEventType;
import com.jarvis.common.event.GenerationFinishedEvent;
import com.jarvis.common.event.TokenEvent;
import com.jarvis.knowledge.KnowledgeDocument;
import com.jarvis.knowledge.KnowledgeException;
import com.jarvis.knowledge.KnowledgeIndex;
import com.jarvis.knowledge.KnowledgeProperties;
import com.jarvis.memory.pipeline.PipelineContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Default controlled research engine backed by the local knowledge index.
 */
@Service
public class DefaultResearchEngine implements ResearchEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultResearchEngine.class);
    private static final int MAX_SEARCHES = 5;
    private static final int MAX_DOCUMENTS = 6;
    private static final int MAX_CONTEXT_CHARS = 25_000;
    private static final int MAX_STEPS = 12;
    private static final Duration TIMEOUT = Duration.ofSeconds(120);

    private final KnowledgeIndex knowledgeIndex;
    private final KnowledgeProperties knowledgeProperties;
    private final List<AIProvider> aiProviders;
    private final CognitiveEventBus cognitiveEventBus;
    private final ObjectMapper objectMapper;

    /**
     * Creates the default research engine.
     *
     * @param knowledgeIndex knowledge metadata index
     * @param knowledgeProperties knowledge properties
     * @param aiProviders available AI providers
     * @param cognitiveEventBus cognitive event bus
     * @param objectMapper JSON mapper
     */
    public DefaultResearchEngine(
            KnowledgeIndex knowledgeIndex,
            KnowledgeProperties knowledgeProperties,
            List<AIProvider> aiProviders,
            CognitiveEventBus cognitiveEventBus,
            ObjectMapper objectMapper
    ) {
        this.knowledgeIndex = knowledgeIndex;
        this.knowledgeProperties = knowledgeProperties;
        this.aiProviders = List.copyOf(aiProviders);
        this.cognitiveEventBus = cognitiveEventBus;
        this.objectMapper = objectMapper;
    }

    @Override
    public PipelineContext research(PipelineContext context) {
        Instant startedAt = Instant.now();
        long startedNano = System.nanoTime();
        AIProvider provider = selectProvider(context);
        ResearchBudget budget = new ResearchBudget();
        StringBuilder observations = new StringBuilder();

        LOGGER.info("[JARVIS][RESEARCH] Research started query=\"{}\"", context.request().message());
        cognitiveEventBus.publish(CognitiveEventType.RESEARCH_STARTED, "STARTED", "Research started", null, Map.of(
                "query", context.request().message(),
                "maxSearches", MAX_SEARCHES,
                "maxDocuments", MAX_DOCUMENTS,
                "maxContextChars", MAX_CONTEXT_CHARS
        ));

        while (budget.canContinue() && Duration.between(startedAt, Instant.now()).compareTo(TIMEOUT) < 0) {
            budget.steps++;
            cognitiveEventBus.publish(CognitiveEventType.RESEARCH_PLANNING, "PLANNING", "Planning next research step", null, Map.of(
                    "step", budget.steps,
                    "searchesUsed", budget.searches,
                    "documentsRead", budget.documents,
                    "contextCharacters", observations.length()
            ));
            ResearchAction action = decide(provider, context, observations.toString(), budget);
            LOGGER.info("[JARVIS][RESEARCH] Step #{} tool={} reason={}", budget.steps, action.tool(), action.reason());
            String tool = normalize(action.tool());
            if ("knowledge.finish".equals(tool) || "finish".equals(tool)) {
                break;
            }
            String result = execute(action, budget);
            if (result.isBlank()) {
                break;
            }
            appendObservation(observations, result);
            if (observations.length() >= MAX_CONTEXT_CHARS) {
                observations.setLength(MAX_CONTEXT_CHARS);
                break;
            }
        }

        StringBuilder responseBuilder = new StringBuilder();
        GenerationFinishedHolder finishedHolder = new GenerationFinishedHolder();
        String finalPrompt = finalPrompt(context, observations.toString());
        provider.stream(context.conversationId(), context.brain(), finalPrompt, AIJobType.CHAT, event -> {
            if (event instanceof TokenEvent tokenEvent) {
                responseBuilder.append(tokenEvent.text());
            }
            if (event instanceof GenerationFinishedEvent finishedEvent) {
                finishedHolder.event = finishedEvent;
            }
            context.modelEventSink().publish(event);
        });

        long durationMs = (System.nanoTime() - startedNano) / 1_000_000;
        cognitiveEventBus.publish(CognitiveEventType.RESEARCH_FINISHED, "FINISHED", "Research finished", null, Map.of(
                "durationMs", durationMs,
                "searches", budget.searches,
                "documentsRead", budget.documents,
                "steps", budget.steps,
                "contextCharacters", observations.length()
        ));
        LOGGER.info("[JARVIS][RESEARCH] Research finished searches={} documents={} contextChars={} durationMs={}",
                budget.searches, budget.documents, observations.length(), durationMs);
        return context.withPrompt(finalPrompt)
                .withResponse(responseBuilder.toString(), finishedHolder.event)
                .withMetadata("researchSearches", budget.searches)
                .withMetadata("researchDocuments", budget.documents)
                .withMetadata("researchContextCharacters", observations.length());
    }

    private ResearchAction decide(AIProvider provider, PipelineContext context, String observations, ResearchBudget budget) {
        String prompt = decisionPrompt(context, observations, budget);
        ChatResponse response = provider.chat(context.brain(), prompt, AIJobType.BACKGROUND);
        try {
            return objectMapper.readValue(extractJson(response.response()), ResearchAction.class);
        } catch (IOException exception) {
            LOGGER.warn("[JARVIS][RESEARCH] Invalid research action JSON. Falling back to finish. raw={}", response.response());
            return new ResearchAction("knowledge.finish", "", "", "", "", "", "Invalid tool JSON");
        }
    }

    private String execute(ResearchAction action, ResearchBudget budget) {
        String tool = normalize(action.tool());
        return switch (tool) {
            case "knowledge.search", "search" -> search(action.query(), budget);
            case "knowledge.list", "list" -> list(action.folder());
            case "knowledge.read", "read" -> read(action.documentId(), budget);
            case "knowledge.find", "find" -> find(action.documentId(), action.phrase(), budget);
            case "knowledge.readsection", "readsection" -> readSection(action.documentId(), action.section(), budget);
            default -> "";
        };
    }

    private String search(String query, ResearchBudget budget) {
        if (budget.searches >= MAX_SEARCHES) {
            return "Search budget exhausted.";
        }
        budget.searches++;
        String normalizedQuery = query == null || query.isBlank() ? "" : query.trim();
        LOGGER.info("[JARVIS][RESEARCH] Search #{} query=\"{}\"", budget.searches, normalizedQuery);
        cognitiveEventBus.publish(CognitiveEventType.RESEARCH_SEARCH_STARTED, "SEARCHING", "Research search started", null, Map.of(
                "searchNumber", budget.searches,
                "query", normalizedQuery
        ));
        List<String> tokens = tokens(normalizedQuery);
        List<KnowledgeDocument> candidates = knowledgeIndex.list().stream()
                .map(document -> new ScoredDocument(document, score(document, tokens)))
                .filter(scored -> scored.score() > 0)
                .sorted(Comparator.comparingInt(ScoredDocument::score).reversed())
                .limit(10)
                .map(ScoredDocument::document)
                .toList();
        cognitiveEventBus.publish(CognitiveEventType.RESEARCH_SEARCH_FINISHED, "FINISHED", "Research search finished", null, Map.of(
                "query", normalizedQuery,
                "candidateDocuments", candidates.size()
        ));
        return "SEARCH \"" + normalizedQuery + "\"\n" + describe(candidates);
    }

    private String list(String folder) {
        String normalized = folder == null ? "" : folder.replace('\\', '/').strip();
        List<KnowledgeDocument> documents = knowledgeIndex.list().stream()
                .filter(document -> normalized.isBlank() || document.relativePath().startsWith(normalized))
                .sorted(Comparator.comparing(KnowledgeDocument::relativePath))
                .limit(30)
                .toList();
        return "LIST \"" + normalized + "\"\n" + describe(documents);
    }

    private String read(String documentId, ResearchBudget budget) {
        Optional<KnowledgeDocument> document = resolve(documentId);
        if (document.isEmpty() || budget.documents >= MAX_DOCUMENTS) {
            return "";
        }
        KnowledgeDocument source = document.get();
        String content = readFile(source.relativePath());
        budget.documents++;
        cognitiveEventBus.publish(CognitiveEventType.RESEARCH_DOCUMENT_SELECTED, "SELECTED", "Research document selected", nodeId(source), Map.of(
                "title", source.title(),
                "relativePath", source.relativePath(),
                "category", source.category()
        ));
        LOGGER.info("[JARVIS][RESEARCH] Document read path={} chars={}", source.relativePath(), content.length());
        cognitiveEventBus.publish(CognitiveEventType.DOCUMENT_READING_STARTED, "READING", "Research reading document", nodeId(source), Map.of(
                "title", source.title(),
                "relativePath", source.relativePath(),
                "category", source.category()
        ));
        cognitiveEventBus.publish(CognitiveEventType.RESEARCH_DOCUMENT_READ, "READ", "Research document read", nodeId(source), Map.of(
                "title", source.title(),
                "relativePath", source.relativePath(),
                "charactersRead", content.length()
        ));
        cognitiveEventBus.publish(CognitiveEventType.SOURCE_ADDED, "ADDED", "Research source used", nodeId(source), Map.of(
                "title", source.title(),
                "relativePath", source.relativePath(),
                "category", source.category(),
                "charactersUsed", Math.min(content.length(), remainingChars())
        ));
        cognitiveEventBus.publish(CognitiveEventType.DOCUMENT_READING_FINISHED, "READ", "Research document read", nodeId(source), Map.of(
                "title", source.title(),
                "relativePath", source.relativePath(),
                "charactersRead", content.length()
        ));
        return "READ " + source.relativePath() + "\n" + truncate(content, remainingChars());
    }

    private String find(String documentId, String phrase, ResearchBudget budget) {
        Optional<KnowledgeDocument> document = resolve(documentId);
        if (document.isEmpty() || phrase == null || phrase.isBlank()) {
            return "";
        }
        String content = readFile(document.get().relativePath());
        String lower = content.toLowerCase(Locale.ROOT);
        int index = lower.indexOf(phrase.toLowerCase(Locale.ROOT));
        if (index < 0) {
            return "FIND " + phrase + " in " + document.get().relativePath() + "\nNo match.";
        }
        int start = Math.max(0, index - 700);
        int end = Math.min(content.length(), index + phrase.length() + 1_200);
        return readExcerpt(document.get(), content.substring(start, end), budget, "FIND " + phrase);
    }

    private String readSection(String documentId, String section, ResearchBudget budget) {
        Optional<KnowledgeDocument> document = resolve(documentId);
        if (document.isEmpty() || section == null || section.isBlank()) {
            return "";
        }
        String content = readFile(document.get().relativePath());
        String[] lines = content.split("\\R");
        StringBuilder excerpt = new StringBuilder();
        boolean collecting = false;
        int headingLevel = Integer.MAX_VALUE;
        for (String line : lines) {
            if (line.trim().matches("#{1,6}\\s+.*")) {
                int level = headingLevel(line);
                if (collecting && level <= headingLevel) {
                    break;
                }
                if (line.toLowerCase(Locale.ROOT).contains(section.toLowerCase(Locale.ROOT))) {
                    collecting = true;
                    headingLevel = level;
                }
            }
            if (collecting) {
                excerpt.append(line).append(System.lineSeparator());
            }
        }
        return readExcerpt(document.get(), excerpt.toString(), budget, "SECTION " + section);
    }

    private String readExcerpt(KnowledgeDocument document, String excerpt, ResearchBudget budget, String label) {
        if (budget.documents >= MAX_DOCUMENTS || excerpt.isBlank()) {
            return "";
        }
        budget.documents++;
        cognitiveEventBus.publish(CognitiveEventType.RESEARCH_SECTION_READ, "READ", "Research section read", nodeId(document), Map.of(
                "title", document.title(),
                "relativePath", document.relativePath(),
                "charactersRead", excerpt.length()
        ));
        cognitiveEventBus.publish(CognitiveEventType.SOURCE_ADDED, "ADDED", "Research source used", nodeId(document), Map.of(
                "title", document.title(),
                "relativePath", document.relativePath(),
                "category", document.category(),
                "charactersUsed", Math.min(excerpt.length(), remainingChars())
        ));
        return label + " " + document.relativePath() + "\n" + truncate(excerpt, remainingChars());
    }

    private String decisionPrompt(PipelineContext context, String observations, ResearchBudget budget) {
        return """
                You are J.A.R.V.I.S. Research Engine.
                Decide the next knowledge tool call.
                Return JSON only. No markdown.

                Available tools:
                {"tool":"knowledge.search","query":"...","reason":"..."}
                {"tool":"knowledge.list","folder":"...","reason":"..."}
                {"tool":"knowledge.read","documentId":"...","reason":"..."}
                {"tool":"knowledge.find","documentId":"...","phrase":"...","reason":"..."}
                {"tool":"knowledge.readSection","documentId":"...","section":"...","reason":"..."}
                {"tool":"knowledge.finish","reason":"..."}

                Budgets:
                searches: %d/%d
                documents: %d/%d
                contextCharacters: %d/%d

                User question:
                %s

                Observations:
                %s
                """.formatted(
                budget.searches, MAX_SEARCHES,
                budget.documents, MAX_DOCUMENTS,
                observations.length(), MAX_CONTEXT_CHARS,
                context.request().message(),
                observations.isBlank() ? "None yet." : observations
        );
    }

    private String finalPrompt(PipelineContext context, String observations) {
        return """
                You are J.A.R.V.I.S.
                Answer the user using the research observations below.
                If the observations do not contain enough information, say what is missing.
                Do not reveal hidden chain-of-thought. Provide the final answer only.

                Conversation context:
                %s

                Research observations:
                %s

                User message:
                %s
                """.formatted(
                context.conversation().isEmpty() ? "No prior conversation." : context.conversation().toString(),
                observations.isBlank() ? "No knowledge observations were collected." : observations,
                context.request().message()
        );
    }

    private void appendObservation(StringBuilder observations, String value) {
        if (observations.length() > 0) {
            observations.append(System.lineSeparator()).append(System.lineSeparator());
        }
        observations.append(truncate(value, Math.max(0, MAX_CONTEXT_CHARS - observations.length())));
    }

    private String describe(List<KnowledgeDocument> documents) {
        if (documents.isEmpty()) {
            return "No candidates.";
        }
        StringBuilder builder = new StringBuilder();
        for (KnowledgeDocument document : documents) {
            builder.append("- id=").append(document.id())
                    .append(" path=").append(document.relativePath())
                    .append(" title=").append(document.title())
                    .append(" category=").append(document.category())
                    .append(System.lineSeparator());
        }
        return builder.toString();
    }

    private Optional<KnowledgeDocument> resolve(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.strip().replace('\\', '/');
        if (normalized.startsWith("knowledge-document:")) {
            normalized = normalized.substring("knowledge-document:".length());
        }
        try {
            return knowledgeIndex.findById(UUID.fromString(normalized));
        } catch (IllegalArgumentException ignored) {
            return knowledgeIndex.findByRelativePath(normalized);
        }
    }

    private String readFile(String relativePath) {
        Path root = Path.of(knowledgeProperties.root()).toAbsolutePath().normalize();
        Path path = root.resolve(relativePath).normalize();
        if (!path.startsWith(root)) {
            throw new KnowledgeException("Rejected knowledge path outside root: " + relativePath);
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new KnowledgeException("Failed to read knowledge source " + relativePath, exception);
        }
    }

    private int score(KnowledgeDocument document, List<String> tokens) {
        return tokens.stream().mapToInt(token ->
                occurrences(document.title(), token) * 100
                        + occurrences(document.category(), token) * 60
                        + occurrences(document.relativePath(), token) * 50
                        + occurrences(document.preview(), token) * 20
        ).sum();
    }

    private int occurrences(String value, String token) {
        if (value == null || value.isBlank() || token == null || token.isBlank()) {
            return 0;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        int count = 0;
        int index = lower.indexOf(token.toLowerCase(Locale.ROOT));
        while (index >= 0) {
            count++;
            index = lower.indexOf(token.toLowerCase(Locale.ROOT), index + token.length());
        }
        return count;
    }

    private List<String> tokens(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return List.of(value.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")).stream()
                .filter(token -> !token.isBlank())
                .distinct()
                .toList();
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replace("_", "").strip();
    }

    private String extractJson(String value) {
        if (value == null) {
            return "{}";
        }
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        return start >= 0 && end > start ? value.substring(start, end + 1) : value;
    }

    private int headingLevel(String line) {
        int count = 0;
        while (count < line.length() && line.charAt(count) == '#') {
            count++;
        }
        return count;
    }

    private int remainingChars() {
        return 4_000;
    }

    private String truncate(String value, int max) {
        if (value == null || max <= 0) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max).stripTrailing();
    }

    private String nodeId(KnowledgeDocument document) {
        return "knowledge-document:" + document.relativePath().replace('\\', '/');
    }

    private AIProvider selectProvider(PipelineContext context) {
        return aiProviders.stream()
                .filter(provider -> provider.provider().equalsIgnoreCase(context.brain().provider()))
                .findFirst()
                .orElseThrow(() -> new AIProviderException("AI provider is not available: " + context.brain().provider()));
    }

    private record ScoredDocument(KnowledgeDocument document, int score) {
    }

    private static final class ResearchBudget {
        private int searches;
        private int documents;
        private int steps;

        private boolean canContinue() {
            return steps < MAX_STEPS && (searches < MAX_SEARCHES || documents < MAX_DOCUMENTS);
        }
    }

    private static final class GenerationFinishedHolder {
        private GenerationFinishedEvent event;
    }
}
