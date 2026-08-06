package com.jarvis.memory.research;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.common.ai.AIJobType;
import com.jarvis.common.ai.AIProvider;
import com.jarvis.common.ai.AIProviderException;
import com.jarvis.common.dto.ChatResponse;
import com.jarvis.common.event.CognitiveEventBus;
import com.jarvis.common.event.CognitiveEventType;
import com.jarvis.common.event.GenerationFinishedEvent;
import com.jarvis.common.event.TokenEvent;
import com.jarvis.common.memory.ConversationMessage;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Reliable research engine with explicit state, robust parsing, and grounded source usage.
 */
@Service
public class DefaultResearchEngine implements ResearchEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultResearchEngine.class);
    private static final int MAX_STEPS = 8;
    private static final int MAX_SEARCHES = 3;
    private static final int MAX_DOCUMENTS_READ = 5;
    private static final int MAX_CHARACTERS_PER_DOCUMENT = 10_000;
    private static final int MAX_TOTAL_CHARACTERS = 30_000;
    private static final Duration TIMEOUT = Duration.ofSeconds(180);

    private final KnowledgeIndex knowledgeIndex;
    private final KnowledgeProperties knowledgeProperties;
    private final List<AIProvider> aiProviders;
    private final CognitiveEventBus cognitiveEventBus;
    private final ObjectMapper objectMapper;
    private final ResearchActionParser actionParser;
    private final ResearchCompletionValidator completionValidator;
    private final ResearchDebugService debugService;

    /**
     * Creates the default research engine.
     *
     * @param knowledgeIndex knowledge metadata index
     * @param knowledgeProperties knowledge properties
     * @param aiProviders available providers
     * @param cognitiveEventBus cognitive event bus
     * @param objectMapper object mapper
     * @param actionParser action parser
     * @param completionValidator completion validator
     * @param debugService debug service
     */
    public DefaultResearchEngine(
            KnowledgeIndex knowledgeIndex,
            KnowledgeProperties knowledgeProperties,
            List<AIProvider> aiProviders,
            CognitiveEventBus cognitiveEventBus,
            ObjectMapper objectMapper,
            ResearchActionParser actionParser,
            ResearchCompletionValidator completionValidator,
            ResearchDebugService debugService
    ) {
        this.knowledgeIndex = knowledgeIndex;
        this.knowledgeProperties = knowledgeProperties;
        this.aiProviders = List.copyOf(aiProviders);
        this.cognitiveEventBus = cognitiveEventBus;
        this.objectMapper = objectMapper;
        this.actionParser = actionParser;
        this.completionValidator = completionValidator;
        this.debugService = debugService;
    }

    @Override
    public PipelineContext research(PipelineContext pipeline) {
        Instant startedAt = Instant.now();
        long startedNano = System.nanoTime();
        AIProvider provider = selectProvider(pipeline);
        ResearchContext context = new ResearchContext(
                pipeline.requestId(),
                pipeline.conversationId(),
                pipeline.request().message(),
                pipeline.effectiveKnowledgeMode().name(),
                pipeline.brain().reasoningLevel()
        );
        debugService.put(context);
        publish(CognitiveEventType.RESEARCH_STARTED, "STARTED", "Research started", null, context, Map.of(
                "query", context.originalQuery(),
                "maxSteps", MAX_STEPS,
                "maxSearches", MAX_SEARCHES,
                "maxDocumentsRead", MAX_DOCUMENTS_READ,
                "maxTotalCharacters", MAX_TOTAL_CHARACTERS
        ));

        try {
            while (canContinue(context, startedAt)) {
                int step = context.nextStep();
                transition(context, ResearchState.PLANNING);
                publish(CognitiveEventType.RESEARCH_PLANNING_STARTED, "PLANNING", "Research planning started", null, context, Map.of(
                        "stepNumber", step
                ));
                ResearchAction action = nextAction(provider, pipeline, context);
                context.addAction(action);
                ResearchActionType type = actionParser.type(action);
                publish(CognitiveEventType.RESEARCH_ACTION_SELECTED, "SELECTED", "Research action selected", null, context, Map.of(
                        "action", type.name(),
                        "query", safe(action.query()),
                        "documentId", safe(action.documentId()),
                        "reason", safe(action.reason())
                ));
                LOGGER.info("[RESEARCH][requestId={}][step={}] ACTION {} query=\"{}\" documentId={}",
                        context.requestId(), step, type, safe(action.query()), safe(action.documentId()));

                if (type == ResearchActionType.FINAL_ANSWER && completionValidator.canAnswer(context)) {
                    transition(context, ResearchState.READY_TO_ANSWER);
                    break;
                }
                if (type == ResearchActionType.FINAL_ANSWER) {
                    action = fallbackAction(context);
                    type = actionParser.type(action);
                    context.addAction(action);
                }
                execute(type, action, context);
            }
        } catch (RuntimeException exception) {
            context.addError(exception.getMessage());
            transition(context, ResearchState.FAILED);
            publish(CognitiveEventType.RESEARCH_ERROR, "ERROR", "Research failed", null, context, Map.of(
                    "error", exception.getMessage() == null ? "" : exception.getMessage()
            ));
            throw exception;
        }

        if (!context.hasReadContent() && context.hasCandidates()) {
            execute(ResearchActionType.READ_DOCUMENT, fallbackAction(context), context);
        }
        transition(context, ResearchState.READY_TO_ANSWER);
        publish(CognitiveEventType.RESEARCH_READY_TO_ANSWER, "READY", "Research ready to answer", null, context, Map.of(
                "usedDocumentIds", List.copyOf(context.usedDocumentIds())
        ));

        StringBuilder responseBuilder = new StringBuilder();
        GenerationFinishedHolder finishedHolder = new GenerationFinishedHolder();
        String finalPrompt = finalPrompt(pipeline, context);
        selectProvider(pipeline).stream(pipeline.conversationId(), finalAnswerBrain(pipeline), finalPrompt, AIJobType.CHAT, event -> {
            if (event instanceof TokenEvent tokenEvent) {
                responseBuilder.append(tokenEvent.text());
            }
            if (event instanceof GenerationFinishedEvent finishedEvent) {
                finishedHolder.event = finishedEvent;
            }
            pipeline.modelEventSink().publish(event);
        });
        String finalAnswer = responseBuilder.toString().strip();
        if (finalAnswer.isBlank()) {
            finalAnswer = fallbackFinalAnswer(context);
            context.addError("Final answer model returned no answer tokens");
            LOGGER.warn("[RESEARCH][requestId={}] EMPTY_FINAL_ANSWER using fallback", context.requestId());
            publishFallbackAnswer(context, finalAnswer);
        }
        context.setFinalAnswer(finalAnswer);
        transition(context, ResearchState.FINISHED);

        long durationMs = (System.nanoTime() - startedNano) / 1_000_000;
        publish(CognitiveEventType.RESPONSE_GROUNDING, "GROUNDED", "Research grounding", null, context, Map.of(
                "grounded", context.hasReadContent(),
                "usedDocumentIds", List.copyOf(context.usedDocumentIds()),
                "usedMemoryIds", List.of(),
                "researchSteps", context.stepNumber()
        ));
        publish(CognitiveEventType.RESEARCH_FINISHED, "FINISHED", "Research finished", null, context, Map.of(
                "durationMs", durationMs,
                "steps", context.stepNumber(),
                "searches", context.searchCount(),
                "documentsRead", context.documentReadCount(),
                "usedDocumentIds", List.copyOf(context.usedDocumentIds())
        ));
        LOGGER.info("[RESEARCH][requestId={}] FINISHED steps={} searches={} documentsRead={} durationMs={}",
                context.requestId(), context.stepNumber(), context.searchCount(), context.documentReadCount(), durationMs);
        return pipeline.withPrompt(finalPrompt)
                .withResponse(finalAnswer, finishedHolder.event)
                .withMetadata("researchSteps", context.stepNumber())
                .withMetadata("usedDocumentIds", List.copyOf(context.usedDocumentIds()))
                .withMetadata("grounded", context.hasReadContent());
    }

    private ResearchAction nextAction(AIProvider provider, PipelineContext pipeline, ResearchContext context) {
        ResearchAction deterministic = deterministicAction(context);
        if (deterministic != null) {
            LOGGER.info("[RESEARCH][requestId={}] AUTOMATIC_FALLBACK_ACTION {}", context.requestId(), deterministic.action());
            return deterministic;
        }
        ChatResponse response = provider.chat(pipeline.brain(), decisionPrompt(pipeline, context), AIJobType.BACKGROUND);
        return actionParser.parseOrFallback(response.response(), provider, pipeline.brain(), repairPrompt(context), context);
    }

    private ResearchAction deterministicAction(ResearchContext context) {
        if (!context.hasCandidates() && context.searchCount() == 0) {
            return ResearchAction.of(ResearchActionType.SEARCH_KNOWLEDGE, context.originalQuery(), "");
        }
        if (context.hasCandidates() && !context.hasReadContent()) {
            return ResearchAction.of(ResearchActionType.READ_DOCUMENT, context.originalQuery(), nodeId(context.bestCandidate()));
        }
        return null;
    }

    private ResearchAction fallbackAction(ResearchContext context) {
        if (context.hasCandidates() && !context.hasReadContent()) {
            return ResearchAction.of(ResearchActionType.READ_DOCUMENT, context.originalQuery(), nodeId(context.bestCandidate()));
        }
        if (context.hasReadContent()) {
            return new ResearchAction(ResearchActionType.FINAL_ANSWER.name(), "", "", "", "", "", "", 0, 0, 0, "", List.copyOf(context.usedDocumentIds()), "grounded content available");
        }
        return ResearchAction.of(ResearchActionType.SEARCH_KNOWLEDGE, context.originalQuery(), "");
    }

    private void execute(ResearchActionType type, ResearchAction action, ResearchContext context) {
        switch (type) {
            case SEARCH_KNOWLEDGE -> search(action.query(), action.limit(), context);
            case LIST_KNOWLEDGE -> list(action.folder(), context);
            case READ_DOCUMENT -> read(action.documentId(), action.maxCharacters(), context);
            case FIND_IN_DOCUMENT -> find(action.documentId(), firstNonBlank(action.query(), action.phrase()), context);
            case READ_SECTION -> readSection(action.documentId(), action.start(), action.maxCharacters(), context);
            case FINAL_ANSWER -> {
            }
        }
    }

    private void search(String query, Integer limit, ResearchContext context) {
        if (context.searchCount() >= MAX_SEARCHES) {
            context.addError("Search budget exhausted");
            return;
        }
        transition(context, ResearchState.SEARCHING);
        context.incrementSearchCount();
        String normalizedQuery = firstNonBlank(query, context.originalQuery());
        publish(CognitiveEventType.KNOWLEDGE_SEARCH_STARTED, "SEARCHING", "Research knowledge search started", null, context, Map.of(
                "query", normalizedQuery
        ));
        List<String> tokens = tokens(normalizedQuery);
        int max = limit == null || limit <= 0 ? 10 : Math.min(10, limit);
        List<KnowledgeDocument> candidates = knowledgeIndex.list().stream()
                .map(document -> new ScoredDocument(document, score(document, tokens)))
                .filter(scored -> scored.score() > 0)
                .sorted(Comparator.comparingInt(ScoredDocument::score).reversed())
                .limit(max)
                .map(ScoredDocument::document)
                .toList();
        context.replaceCandidates(candidates);
        candidates.forEach(document -> publish(CognitiveEventType.KNOWLEDGE_CANDIDATE_FOUND, "FOUND", "Knowledge candidate found", nodeId(document), context, metadata(document, Map.of(
                "score", score(document, tokens)
        ))));
        publish(CognitiveEventType.KNOWLEDGE_SEARCH_FINISHED, "FINISHED", "Research knowledge search finished", null, context, Map.of(
                "query", normalizedQuery,
                "candidateDocuments", candidates.size()
        ));
        context.addObservation(json(Map.of(
                "tool", "knowledge.search",
                "query", normalizedQuery,
                "candidateCount", candidates.size(),
                "candidates", candidates.stream().map(document -> metadata(document, Map.of())).toList()
        )));
        transition(context, candidates.isEmpty() ? ResearchState.NEEDS_MORE_INFORMATION : ResearchState.RESULTS_AVAILABLE);
        LOGGER.info("[RESEARCH][requestId={}][step={}] RESULTS count={}", context.requestId(), context.stepNumber(), candidates.size());
    }

    private void list(String folder, ResearchContext context) {
        String normalized = folder == null ? "" : folder.replace('\\', '/').strip();
        List<KnowledgeDocument> documents = knowledgeIndex.list().stream()
                .filter(document -> normalized.isBlank() || document.relativePath().startsWith(normalized))
                .sorted(Comparator.comparing(KnowledgeDocument::relativePath))
                .limit(30)
                .toList();
        context.replaceCandidates(documents);
        context.addObservation(json(Map.of(
                "tool", "knowledge.list",
                "folder", normalized,
                "documents", documents.stream().map(document -> metadata(document, Map.of())).toList()
        )));
        transition(context, documents.isEmpty() ? ResearchState.NEEDS_MORE_INFORMATION : ResearchState.RESULTS_AVAILABLE);
    }

    private void read(String documentId, Integer maxCharacters, ResearchContext context) {
        Optional<KnowledgeDocument> document = resolve(documentId);
        if (document.isEmpty() || context.documentReadCount() >= MAX_DOCUMENTS_READ || context.totalCharactersRead() >= MAX_TOTAL_CHARACTERS) {
            context.addError("Document read unavailable: " + documentId);
            return;
        }
        KnowledgeDocument source = document.get();
        transition(context, ResearchState.SELECTING_DOCUMENT);
        publish(CognitiveEventType.RESEARCH_DOCUMENT_SELECTED, "SELECTED", "Research document selected", nodeId(source), context, metadata(source, Map.of()));
        transition(context, ResearchState.READING_DOCUMENT);
        publish(CognitiveEventType.DOCUMENT_READ_STARTED, "READING", "Document read started", nodeId(source), context, metadata(source, Map.of()));
        publish(CognitiveEventType.DOCUMENT_READING_STARTED, "READING", "Document read started", nodeId(source), context, metadata(source, Map.of()));
        String content = readFile(source.relativePath());
        int allowed = Math.min(maxCharacters == null || maxCharacters <= 0 ? MAX_CHARACTERS_PER_DOCUMENT : maxCharacters, MAX_CHARACTERS_PER_DOCUMENT);
        allowed = Math.min(allowed, Math.max(0, MAX_TOTAL_CHARACTERS - context.totalCharactersRead()));
        String returned = truncate(content, allowed);
        boolean truncated = returned.length() < content.length();
        context.addReadDocument(nodeId(source), returned.length());
        Map<String, Object> contentMetadata = metadata(source, Map.of(
                "charactersReturned", returned.length(),
                "truncated", truncated
        ));
        publish(CognitiveEventType.DOCUMENT_CONTENT_RECEIVED, "RECEIVED", "Document content received", nodeId(source), context, contentMetadata);
        publish(CognitiveEventType.RESEARCH_DOCUMENT_READ, "READ", "Research document read", nodeId(source), context, contentMetadata);
        publish(CognitiveEventType.RESEARCH_SOURCE_ACCEPTED, "USED", "Research source accepted", nodeId(source), context, contentMetadata);
        publish(CognitiveEventType.SOURCE_ADDED, "ADDED", "Research source used", nodeId(source), context, metadata(source, Map.of(
                "charactersUsed", returned.length()
        )));
        publish(CognitiveEventType.DOCUMENT_READ_FINISHED, "READ", "Document read finished", nodeId(source), context, contentMetadata);
        publish(CognitiveEventType.DOCUMENT_READING_FINISHED, "READ", "Document read finished", nodeId(source), context, contentMetadata);
        context.addObservation(json(Map.of(
                "tool", "knowledge.read",
                "documentId", nodeId(source),
                "relativePath", source.relativePath(),
                "title", source.title(),
                "charactersReturned", returned.length(),
                "truncated", truncated,
                "content", returned
        )));
        transition(context, ResearchState.ANALYZING_DOCUMENT);
        LOGGER.info("[RESEARCH][requestId={}][step={}] CONTENT_RECEIVED characters={} truncated={} documentId={}",
                context.requestId(), context.stepNumber(), returned.length(), truncated, nodeId(source));
    }

    private void find(String documentId, String query, ResearchContext context) {
        Optional<KnowledgeDocument> document = resolve(documentId);
        if (document.isEmpty() || query == null || query.isBlank()) {
            context.addError("Find unavailable");
            return;
        }
        KnowledgeDocument source = document.get();
        publish(CognitiveEventType.DOCUMENT_FIND_STARTED, "FINDING", "Document find started", nodeId(source), context, metadata(source, Map.of(
                "query", query
        )));
        String content = readFile(source.relativePath());
        String lower = content.toLowerCase(Locale.ROOT);
        String lowerQuery = query.toLowerCase(Locale.ROOT);
        int index = lower.indexOf(lowerQuery);
        if (index < 0) {
            publish(CognitiveEventType.DOCUMENT_FIND_FINISHED, "NOT_FOUND", "Document find finished", nodeId(source), context, metadata(source, Map.of(
                    "query", query,
                    "matches", 0
            )));
            context.addObservation(json(Map.of("tool", "knowledge.find", "documentId", nodeId(source), "query", query, "matches", 0)));
            return;
        }
        int start = Math.max(0, index - 900);
        int end = Math.min(content.length(), index + lowerQuery.length() + 1_500);
        String excerpt = content.substring(start, end);
        context.addReadDocument(nodeId(source), excerpt.length());
        publish(CognitiveEventType.DOCUMENT_FIND_MATCH, "MATCH", "Document find match", nodeId(source), context, metadata(source, Map.of(
                "query", query,
                "charactersReturned", excerpt.length()
        )));
        publish(CognitiveEventType.RESEARCH_SOURCE_ACCEPTED, "USED", "Research source accepted", nodeId(source), context, metadata(source, Map.of(
                "charactersUsed", excerpt.length()
        )));
        publish(CognitiveEventType.DOCUMENT_FIND_FINISHED, "FINISHED", "Document find finished", nodeId(source), context, metadata(source, Map.of(
                "query", query,
                "matches", 1
        )));
        context.addObservation(json(Map.of(
                "tool", "knowledge.find",
                "documentId", nodeId(source),
                "relativePath", source.relativePath(),
                "title", source.title(),
                "query", query,
                "content", excerpt
        )));
    }

    private void readSection(String documentId, Integer start, Integer maxCharacters, ResearchContext context) {
        Optional<KnowledgeDocument> document = resolve(documentId);
        if (document.isEmpty()) {
            context.addError("Section read unavailable: " + documentId);
            return;
        }
        String content = readFile(document.get().relativePath());
        int offset = Math.max(0, start == null ? 0 : start);
        if (offset >= content.length()) {
            return;
        }
        int max = Math.min(maxCharacters == null || maxCharacters <= 0 ? MAX_CHARACTERS_PER_DOCUMENT : maxCharacters, MAX_CHARACTERS_PER_DOCUMENT);
        String excerpt = truncate(content.substring(offset), max);
        context.addReadDocument(nodeId(document.get()), excerpt.length());
        publish(CognitiveEventType.RESEARCH_SECTION_READ, "READ", "Research section read", nodeId(document.get()), context, metadata(document.get(), Map.of(
                "start", offset,
                "charactersReturned", excerpt.length(),
                "truncated", offset + excerpt.length() < content.length()
        )));
        publish(CognitiveEventType.RESEARCH_SOURCE_ACCEPTED, "USED", "Research source accepted", nodeId(document.get()), context, metadata(document.get(), Map.of(
                "charactersUsed", excerpt.length()
        )));
        context.addObservation(json(Map.of(
                "tool", "knowledge.readSection",
                "documentId", nodeId(document.get()),
                "relativePath", document.get().relativePath(),
                "title", document.get().title(),
                "charactersReturned", excerpt.length(),
                "content", excerpt
        )));
    }

    private String decisionPrompt(PipelineContext pipeline, ResearchContext context) {
        return """
                You are J.A.R.V.I.S. Research Engine.
                Select exactly one next action. Return JSON only. No markdown. No prose.

                Supported schemas:
                {"action":"SEARCH_KNOWLEDGE","query":"reactor 7 codename","limit":10}
                {"action":"LIST_KNOWLEDGE","folder":"ProjectNova"}
                {"action":"READ_DOCUMENT","documentId":"knowledge-document:ProjectNova/Secret.md"}
                {"action":"FIND_IN_DOCUMENT","documentId":"knowledge-document:ProjectNova/Secret.md","query":"reactor 7 codename"}
                {"action":"READ_SECTION","documentId":"knowledge-document:ProjectNova/Secret.md","start":0,"maxCharacters":8000}
                {"action":"FINAL_ANSWER","answer":"...","usedDocumentIds":["knowledge-document:ProjectNova/Secret.md"]}

                Rules:
                Search results are not document content.
                Do not FINAL_ANSWER a concrete fact until at least one relevant document has been read.
                If results exist and no document was read, choose READ_DOCUMENT for the best result.

                State: %s
                Step: %d/%d
                Searches: %d/%d
                Documents read: %d/%d
                Characters read: %d/%d

                User question:
                %s

                Recent conversation context:
                %s

                Candidate document ids:
                %s

                Read document ids:
                %s

                Observations:
                %s
                """.formatted(
                context.currentState(),
                context.stepNumber(), MAX_STEPS,
                context.searchCount(), MAX_SEARCHES,
                context.documentReadCount(), MAX_DOCUMENTS_READ,
                context.totalCharactersRead(), MAX_TOTAL_CHARACTERS,
                context.originalQuery(),
                conversationContext(pipeline),
                context.candidateDocumentIds(),
                context.readDocumentIds(),
                context.observationsText().isBlank() ? "None." : context.observationsText()
        );
    }

    private String repairPrompt(ResearchContext context) {
        return """
                Your previous tool action was invalid.
                Return exactly one valid JSON action using the supported schema.
                Do not answer the user yet.
                If search results exist and no document was read, return READ_DOCUMENT for the best candidate.

                Candidate document ids:
                %s

                Read document ids:
                %s
                """.formatted(context.candidateDocumentIds(), context.readDocumentIds());
    }

    private String finalPrompt(PipelineContext pipeline, ResearchContext context) {
        return """
                You are J.A.R.V.I.S.
                Produce a visible final answer. Do not return only hidden reasoning.
                Answer the user directly using only the research observations below.
                If the requested fact is present in the content, state it plainly.
                If it is not present after research, say you could not find it.
                Do not say only that the information is located in a file when the content contains the answer.
                Do not reveal hidden chain-of-thought.

                Research observations:
                %s

                Recent conversation context:
                %s

                Used document ids:
                %s

                User question:
                %s
                """.formatted(
                context.observationsText().isBlank() ? "No document content was read." : context.observationsText(),
                conversationContext(pipeline),
                context.usedDocumentIds(),
                pipeline.request().message()
        );
    }

    private com.jarvis.common.ai.Brain finalAnswerBrain(PipelineContext pipeline) {
        return pipeline.brain().withRoutingMetadata("Research final answer", 0L, com.jarvis.common.ai.ReasoningLevel.LOW);
    }

    private String fallbackFinalAnswer(ResearchContext context) {
        if (!context.hasReadContent()) {
            return "Nie znalazłem tej informacji w dostępnej wiedzy.";
        }
        return """
                Nie udało mi się wygenerować odpowiedzi końcowej z modelu, ale odczytałem powiązane źródła.
                Sprawdzone dokumenty: %s
                """.formatted(String.join(", ", context.usedDocumentIds())).strip();
    }

    private void publishFallbackAnswer(ResearchContext context, String answer) {
        publish(CognitiveEventType.ANSWER_STARTED, "ANSWERING", "Fallback answer started", null, context, Map.of(
                "fallback", true
        ));
        publish(CognitiveEventType.ANSWER_TOKEN, "TOKEN", "Fallback answer token", null, context, Map.of(
                "text", answer,
                "index", 1,
                "fallback", true
        ));
        publish(CognitiveEventType.TOKEN, "TOKEN", "Fallback token", null, context, Map.of(
                "text", answer,
                "index", 1,
                "fallback", true
        ));
        publish(CognitiveEventType.ANSWER_FINISHED, "FINISHED", "Fallback answer finished", null, context, Map.of(
                "characters", answer.length(),
                "tokens", 1,
                "fallback", true
        ));
    }

    private String conversationContext(PipelineContext pipeline) {
        if (pipeline.conversation().isEmpty()) {
            return "None.";
        }
        StringBuilder builder = new StringBuilder();
        for (ConversationMessage message : pipeline.conversation()) {
            builder.append(message.role().name())
                    .append(":\n")
                    .append(message.content())
                    .append("\n\n");
        }
        return builder.toString().strip();
    }

    private boolean canContinue(ResearchContext context, Instant startedAt) {
        return context.stepNumber() < MAX_STEPS
                && context.searchCount() <= MAX_SEARCHES
                && context.documentReadCount() < MAX_DOCUMENTS_READ
                && context.totalCharactersRead() < MAX_TOTAL_CHARACTERS
                && Duration.between(startedAt, Instant.now()).compareTo(TIMEOUT) < 0
                && context.currentState() != ResearchState.READY_TO_ANSWER
                && context.currentState() != ResearchState.FINISHED
                && context.currentState() != ResearchState.FAILED;
    }

    private void transition(ResearchContext context, ResearchState next) {
        ResearchState previous = context.currentState();
        context.transitionTo(next);
        LOGGER.info("[RESEARCH][requestId={}] STATE {} -> {}", context.requestId(), previous, next);
    }

    private void publish(CognitiveEventType type, String status, String message, String nodeId, ResearchContext context, Map<String, Object> metadata) {
        Map<String, Object> enriched = new LinkedHashMap<>(metadata);
        enriched.put("requestId", context.requestId());
        enriched.put("conversationId", context.conversationId());
        enriched.put("stepNumber", context.stepNumber());
        enriched.put("researchState", context.currentState().name());
        if (nodeId != null && !nodeId.isBlank()) {
            enriched.put("nodeId", nodeId);
        }
        cognitiveEventBus.publish(type, status, message, nodeId, Map.copyOf(enriched));
    }

    private Map<String, Object> metadata(KnowledgeDocument document, Map<String, Object> extra) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("documentId", nodeId(document));
        metadata.put("rawDocumentId", document.id().toString());
        metadata.put("nodeId", nodeId(document));
        metadata.put("relativePath", document.relativePath());
        metadata.put("title", document.title());
        metadata.put("category", document.category());
        metadata.put("ancestryNodeIds", ancestryNodeIds(document.relativePath()));
        metadata.putAll(extra);
        return metadata;
    }

    private List<String> ancestryNodeIds(String relativePath) {
        String normalized = relativePath.replace('\\', '/');
        java.util.ArrayList<String> ancestry = new java.util.ArrayList<>();
        int index = normalized.indexOf('/');
        while (index >= 0) {
            ancestry.add("knowledge-folder:" + normalized.substring(0, index));
            index = normalized.indexOf('/', index + 1);
        }
        ancestry.add("knowledge-document:" + normalized);
        return List.copyOf(ancestry);
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
        String fileName = fileName(document.relativePath());
        return tokens.stream().mapToInt(token ->
                exact(document.title(), token) * 220
                        + exact(fileName, token) * 220
                        + occurrences(document.title(), token) * 120
                        + occurrences(fileName, token) * 110
                        + occurrences(document.category(), token) * 70
                        + occurrences(document.relativePath(), token) * 70
                        + occurrences(document.preview(), token) * 25
        ).sum();
    }

    private int exact(String value, String token) {
        if (value == null || token == null) {
            return 0;
        }
        String normalized = value.toLowerCase(Locale.ROOT)
                .replace(".md", "")
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .strip();
        return normalized.equals(token.toLowerCase(Locale.ROOT)) ? 1 : 0;
    }

    private String fileName(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return "";
        }
        String normalized = relativePath.replace('\\', '/');
        int index = normalized.lastIndexOf('/');
        return index < 0 ? normalized : normalized.substring(index + 1);
    }

    private int occurrences(String value, String token) {
        if (value == null || value.isBlank() || token == null || token.isBlank()) {
            return 0;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        String lowerToken = token.toLowerCase(Locale.ROOT);
        int count = 0;
        int index = lower.indexOf(lowerToken);
        while (index >= 0) {
            count++;
            index = lower.indexOf(lowerToken, index + lowerToken.length());
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

    private String truncate(String value, int max) {
        if (value == null || max <= 0) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max).stripTrailing();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            return String.valueOf(value);
        }
    }

    private String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private String safe(String value) {
        return value == null ? "" : value;
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

    private static final class GenerationFinishedHolder {
        private GenerationFinishedEvent event;
    }
}
