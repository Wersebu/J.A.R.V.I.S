package com.jarvis.tools.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.common.ai.AIJobType;
import com.jarvis.common.ai.AIProvider;
import com.jarvis.common.ai.AIProviderException;
import com.jarvis.common.event.CognitiveEventBus;
import com.jarvis.common.event.CognitiveEventType;
import com.jarvis.common.knowledge.KnowledgeMode;
import com.jarvis.tools.ToolException;
import com.jarvis.tools.ToolManager;
import com.jarvis.tools.ToolRequest;
import com.jarvis.tools.ToolResult;
import com.jarvis.tools.ToolRuntimeProperties;
import com.jarvis.tools.schema.ToolArgumentDefinition;
import com.jarvis.tools.schema.ToolDefinition;
import com.jarvis.tools.schema.ToolOperationDefinition;
import com.jarvis.tools.schema.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Default native LLM-owned tool-calling runtime.
 */
@Service
public class DefaultToolCallingRuntime implements ToolCallingRuntime {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultToolCallingRuntime.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final String AI_UNAVAILABLE_WRITE_MESSAGE =
            "Nie moge teraz przeanalizowac i zapisac tej informacji, poniewaz model AI jest niedostepny.";
    private static final Pattern SPECIFIC_VALUE_PATTERN = Pattern.compile(
            "(?iu)(?:\\b\\d+[\\d .,\u00a0]{0,24}\\s*(?:zl|zł|pln|usd|eur|gbp|\\$|€)\\b|(?:\\$|€)\\s*\\d+[\\d .,\\u00a0]{0,24})"
    );

    private final List<AIProvider> aiProviders;
    private final ToolManager toolManager;
    private final ToolRegistry toolRegistry;
    private final ToolIntentDetector intentDetector;
    private final ToolRuntimeProperties properties;
    private final CognitiveEventBus cognitiveEventBus;
    private final ToolRuntimeDebugService debugService;
    private final ObjectMapper objectMapper;
    private final WebSearchQualityEvaluator webSearchQualityEvaluator;

    /**
     * Creates the runtime.
     */
    public DefaultToolCallingRuntime(
            List<AIProvider> aiProviders,
            ToolManager toolManager,
            ToolRegistry toolRegistry,
            ToolIntentDetector intentDetector,
            ToolRuntimeProperties properties,
            CognitiveEventBus cognitiveEventBus,
            ToolRuntimeDebugService debugService,
            ObjectMapper objectMapper
    ) {
        this.aiProviders = List.copyOf(aiProviders);
        this.toolManager = toolManager;
        this.toolRegistry = toolRegistry;
        this.intentDetector = intentDetector;
        this.properties = properties;
        this.cognitiveEventBus = cognitiveEventBus;
        this.debugService = debugService;
        this.objectMapper = objectMapper;
        this.webSearchQualityEvaluator = new WebSearchQualityEvaluator();
    }

    @Override
    public ToolCallingResult execute(ToolCallingRequest request) {
        ToolIntent intent = resolveIntent(request);
        if (!properties.isEnabled()) {
            return new ToolCallingResult(false, "", List.of(), List.of());
        }

        Instant started = Instant.now();
        List<ToolRuntimeStep> steps = new ArrayList<>();
        List<ToolResult> results = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int maxCalls = request.knowledgeMode() == KnowledgeMode.RESEARCH
                ? properties.maxCallsResearch()
                : properties.maxCallsFast();
        if (intent == ToolIntent.SEARCH_WEB) {
            maxCalls = Math.max(maxCalls, 8);
        }
        int failures = 0;
        String observation = "";

        LOGGER.info("[TOOL_LOOP] requestId={} intent={} mode={}", request.requestId(), intent, request.knowledgeMode());
        publish(request, CognitiveEventType.TOOL_LOOP_STARTED, "STARTED", "Tool loop started", null, 0,
                Map.of("intent", intent.name(), "mode", request.knowledgeMode().name(), "decisionOwner", "LLM"));

        try {
            for (int step = 1; step <= maxCalls; step++) {
                if (Duration.between(started, Instant.now()).toSeconds() > properties.timeoutSeconds()) {
                    errors.add("Tool loop timeout");
                    break;
                }

                ToolAction action;
                if (step == 1 && results.isEmpty() && intent == ToolIntent.SEARCH_WEB) {
                    action = initialWebAction(request);
                    publish(request, CognitiveEventType.TOOL_SELECTION_STARTED, "SELECTING",
                            "Mapping main model web request to WebSearchTool", targetNode(action), step,
                            Map.of("decisionOwner", "MAIN_MODEL", "tool", "web", "operation", action.operation()));
                } else {
                    publish(request, CognitiveEventType.TOOL_SELECTION_STARTED, "SELECTING", "Asking LLM for next tool action",
                            null, step, Map.of("decisionOwner", "LLM"));
                    action = normalizeAction(nextAction(request, intent, observation, step));
                }
                if (isNoTool(action)) {
                    steps.add(new ToolRuntimeStep(step, "NO_TOOL", "", "", "DECLINED", null));
                    saveDebug(request, intent, steps, "NO_TOOL", errors);
                    publish(request, CognitiveEventType.TOOL_LOOP_FINISHED, "NO_TOOL",
                            "LLM decided no tool is needed", null, step, Map.of("decisionOwner", "LLM"));
                    return new ToolCallingResult(false, "", steps, results);
                }
                if ("FINAL_ANSWER".equalsIgnoreCase(action.action())) {
                    if (step == 1 && results.isEmpty() && intent == ToolIntent.NO_TOOL) {
                        LOGGER.info("[TOOL_LOOP] firstAction=FINAL_ANSWER retryingContextualToolDecision intent={}", intent);
                        action = normalizeAction(retryContextualToolDecision(request, action.answer(), step));
                        if (isNoTool(action) || "FINAL_ANSWER".equalsIgnoreCase(action.action())) {
                            steps.add(new ToolRuntimeStep(step, isNoTool(action) ? "NO_TOOL" : "FINAL_ANSWER",
                                    "", "", "DECLINED", null));
                            saveDebug(request, intent, steps, "NO_TOOL", errors);
                            publish(request, CognitiveEventType.TOOL_LOOP_FINISHED, "NO_TOOL",
                                    "LLM decided no tool is needed", null, step, Map.of("decisionOwner", "LLM"));
                            return new ToolCallingResult(false, "", steps, results);
                        }
                    }
                    if ("FINAL_ANSWER".equalsIgnoreCase(action.action())) {
                        if (step == 1 && results.isEmpty() && requiresToolAttempt(intent)) {
                            LOGGER.info("[TOOL_LOOP] firstAction=FINAL_ANSWER retryingWithExplicitToolSchema intent={}", intent);
                            action = normalizeAction(retryToolAction(request, intent, action.answer(), step));
                            if ("FINAL_ANSWER".equalsIgnoreCase(action.action())) {
                                steps.add(new ToolRuntimeStep(step, "FINAL_ANSWER", "", "", "FINISHED", null));
                                saveDebug(request, intent, steps, "FINISHED_WITHOUT_TOOL", errors);
                                publish(request, CognitiveEventType.TOOL_LOOP_FINISHED, "FINISHED_WITHOUT_TOOL",
                                        "LLM declined to use a tool", null, step, Map.of("decisionOwner", "LLM"));
                                return new ToolCallingResult(true, action.answer(), steps, results);
                            }
                        }
                        if ("FINAL_ANSWER".equalsIgnoreCase(action.action())) {
                            steps.add(new ToolRuntimeStep(step, "FINAL_ANSWER", "", "", "FINISHED", null));
                            saveDebug(request, intent, steps, "FINISHED", errors);
                            publish(request, CognitiveEventType.TOOL_LOOP_FINISHED, "FINISHED", "Tool loop finished", null, step, Map.of());
                            return new ToolCallingResult(true, action.answer(), steps, results);
                        }
                    }
                }
                if (isNoTool(action)) {
                    steps.add(new ToolRuntimeStep(step, "NO_TOOL", "", "", "DECLINED", null));
                    saveDebug(request, intent, steps, "NO_TOOL", errors);
                    publish(request, CognitiveEventType.TOOL_LOOP_FINISHED, "NO_TOOL",
                            "LLM decided no tool is needed", null, step, Map.of("decisionOwner", "LLM"));
                    return new ToolCallingResult(false, "", steps, results);
                }

                publish(request, CognitiveEventType.TOOL_CALL_PROPOSED, "PROPOSED", "LLM proposed tool call", null, step,
                        actionMetadata(action));
                try {
                    validate(action);
                } catch (ToolException exception) {
                    errors.add(exception.getMessage());
                    LOGGER.warn("[TOOL_LOOP] invalid tool action step={} error={}", step, exception.getMessage());
                    action = normalizeAction(retryInvalidToolAction(request, intent, action, exception.getMessage(), step));
                    if ("FINAL_ANSWER".equalsIgnoreCase(action.action())) {
                        steps.add(new ToolRuntimeStep(step, "FINAL_ANSWER", "", "", "FINISHED_AFTER_INVALID_ACTION", null));
                        saveDebug(request, intent, steps, "FINISHED_AFTER_INVALID_ACTION", errors);
                        publish(request, CognitiveEventType.TOOL_LOOP_FINISHED, "FINISHED_AFTER_INVALID_ACTION",
                                "LLM stopped tool loop after invalid action", null, step, Map.of("errors", errors));
                        return new ToolCallingResult(true, action.answer(), steps, results);
                    }
                    try {
                        validate(action);
                    } catch (ToolException retryException) {
                        errors.add(retryException.getMessage());
                        LOGGER.warn("[TOOL_LOOP] invalid repaired tool action step={} error={}", step, retryException.getMessage());
                        failures++;
                        if (failures >= properties.maxConsecutiveFailures()) {
                            break;
                        }
                        continue;
                    }
                }
                publish(request, CognitiveEventType.TOOL_CALL_VALIDATED, "VALIDATED", "Tool call validated", null, step,
                        actionMetadata(action));

                ToolRequest toolRequest = new ToolRequest(
                        action.tool(),
                        action.operation(),
                        request.conversationId(),
                        request.requestId(),
                        action.reason(),
                        "Native LLM tool loop step " + step,
                        action.arguments()
                );

                publish(request, CognitiveEventType.TOOL_EXECUTION_STARTED, "EXECUTING", "Tool execution started",
                        targetNode(action), step, actionMetadata(action));
                LOGGER.info("[TOOL_CALL] tool={} operation={} step={}", action.tool(), action.operation(), step);
                ToolResult result = toolManager.execute(toolRequest);
                if (isTerminalWebSearch(action)) {
                    result = enrichWebSearchQuality(request, result, step);
                }
                results.add(result);
                steps.add(new ToolRuntimeStep(step, "TOOL_CALL", action.tool(), action.operation(),
                        result.success() ? "OK" : "FAILED", result));
                publish(request, CognitiveEventType.TOOL_EXECUTION_FINISHED, result.success() ? "FINISHED" : "FAILED",
                        "Tool execution finished", targetNode(action), step, resultMetadata(result));
                publish(request, CognitiveEventType.TOOL_RESULT_RECEIVED, "OBSERVED", "Tool result received",
                        targetNode(action), step, resultMetadata(result));
                LOGGER.info("[TOOL_RESULT] success={} requiresApproval={} draftId={}",
                        result.success(), result.requiresApproval(), result.draftId());

                if (result.requiresApproval()) {
                    publish(request, CognitiveEventType.TOOL_APPROVAL_REQUIRED, "WAITING_APPROVAL",
                            "Tool call requires approval", targetNode(action), step, resultMetadata(result));
                    saveDebug(request, intent, steps, "WAITING_APPROVAL", errors);
                    publish(request, CognitiveEventType.TOOL_LOOP_FINISHED, "WAITING_APPROVAL", "Tool loop waiting for approval",
                            targetNode(action), step, resultMetadata(result));
                    return new ToolCallingResult(true, "", steps, results);
                }
                if (!result.success()) {
                    failures++;
                    if (isTerminalFailedSearch(action)) {
                        errors.add(result.errorMessage().isBlank() ? "Web search failed" : result.errorMessage());
                        break;
                    }
                    if (failures >= properties.maxConsecutiveFailures()) {
                        errors.add(result.errorMessage().isBlank() ? "Tool execution failed" : result.errorMessage());
                        break;
                    }
                } else {
                    failures = 0;
                }
                observation = observation(result);

                if (result.success() && isTerminalWebSearch(action) && webSearchAccepted(result)) {
                    saveDebug(request, intent, steps, "FINISHED", errors);
                    publish(request, CognitiveEventType.TOOL_LOOP_FINISHED, "FINISHED", "Tool loop finished",
                            targetNode(action), step, resultMetadata(result));
                    return new ToolCallingResult(true, "", steps, results);
                }
                if (result.success() && isWebPageRead(action)) {
                    result = enrichWebPageQuality(request, result, step, action);
                    results.set(results.size() - 1, result);
                    observation = observation(result);
                    if (webPageAccepted(result)) {
                        saveDebug(request, intent, steps, "FINISHED", errors);
                        publish(request, CognitiveEventType.TOOL_LOOP_FINISHED, "FINISHED", "Tool loop finished",
                                targetNode(action), step, resultMetadata(result));
                        return new ToolCallingResult(true, "", steps, results);
                    }
                    continue;
                }

                if (!isTerminalWebSearch(action)) {
                    publish(request, CognitiveEventType.TOOL_VERIFICATION_STARTED, "VERIFYING", "Verifying tool result",
                            targetNode(action), step, Map.of("operation", action.operation()));
                    publish(request, CognitiveEventType.TOOL_VERIFICATION_FINISHED, "VERIFIED", "Tool result verified",
                            targetNode(action), step, Map.of("operation", action.operation(), "success", result.success()));
                }
                if (result.success() && isTerminalWrite(action.operation())) {
                    saveDebug(request, intent, steps, "FINISHED", errors);
                    publish(request, CognitiveEventType.TOOL_LOOP_FINISHED, "FINISHED", "Tool loop finished",
                            targetNode(action), step, resultMetadata(result));
                    return new ToolCallingResult(true, "", steps, results);
                }
                if (!result.success() && isWebPageRead(action)) {
                    errors.add(result.errorMessage().isBlank() ? "Web page read failed" : result.errorMessage());
                    break;
                }
            }
            String finalAnswer = errors.isEmpty()
                    ? "Zakonczylem prace narzedziowa bez dodatkowych operacji."
                    : "Nie moglem bezpiecznie dokonczyc pracy narzedziowej: " + String.join("; ", errors);
            saveDebug(request, intent, steps, errors.isEmpty() ? "FINISHED" : "FAILED", errors);
            publish(request, CognitiveEventType.TOOL_LOOP_FINISHED, errors.isEmpty() ? "FINISHED" : "FAILED",
                    "Tool loop finished", null, steps.size(), Map.of("errors", errors));
            return new ToolCallingResult(true, finalAnswer, steps, results);
        } catch (AIProviderException exception) {
            errors.add(exception.getMessage() == null ? "AI provider unavailable" : exception.getMessage());
            saveDebug(request, intent, steps, "AI_UNAVAILABLE", errors);
            publish(request, CognitiveEventType.TOOL_LOOP_ERROR, "AI_UNAVAILABLE", "AI provider unavailable", null,
                    steps.size(), Map.of("error", safe(exception.getMessage()), "noWritePerformed", true));
            LOGGER.warn("[TOOL_LOOP] AI provider unavailable; no tool write performed requestId={}", request.requestId());
            return new ToolCallingResult(true, AI_UNAVAILABLE_WRITE_MESSAGE, steps, results);
        } catch (RuntimeException exception) {
            errors.add(exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage());
            saveDebug(request, intent, steps, "FAILED", errors);
            publish(request, CognitiveEventType.TOOL_LOOP_ERROR, "ERROR", "Tool loop failed", null, steps.size(), Map.of(
                    "error", exception.getMessage() == null ? "" : exception.getMessage()
            ));
            throw exception;
        }
    }

    private ToolAction nextAction(ToolCallingRequest request, ToolIntent intent, String observation, int step) {
        String prompt = prompt(request, intent, observation, step);
        String raw = selectProvider(request).chat(request.brain(), prompt, AIJobType.BACKGROUND).response();
        try {
            return parse(raw);
        } catch (RuntimeException exception) {
            LOGGER.warn("[TOOL_LOOP] invalid action JSON step={} raw={}", step, abbreviate(raw));
            String repaired = selectProvider(request).chat(request.brain(), repairPrompt(raw), AIJobType.BACKGROUND).response();
            try {
                ToolAction repairedAction = parse(repaired);
                ToolAction webContinuation = webContinuationAfterUnsafeStep(request, intent, observation, repaired,
                        "Repaired web action declined after previous web results; continue by reading the best result.");
                if (isNoTool(repairedAction) && webContinuation != null) {
                    return webContinuation;
                }
                return repairedAction;
            } catch (RuntimeException repairException) {
                LOGGER.warn("[TOOL_LOOP] action JSON repair failed step={} repaired={}", step, abbreviate(repaired));
                return fallbackAfterInvalidToolJson(request, intent, observation, raw,
                        "I could not convert the model response into a safe tool action.");
            }
        }
    }

    private ToolAction retryToolAction(ToolCallingRequest request, ToolIntent intent, String previousAnswer, int step) {
        String raw = selectProvider(request).chat(request.brain(), retryPrompt(request, intent, previousAnswer, step), AIJobType.BACKGROUND).response();
        try {
            return parse(raw);
        } catch (RuntimeException exception) {
            LOGGER.warn("[TOOL_LOOP] retry action JSON invalid step={} raw={}", step, abbreviate(raw));
            return fallbackAfterInvalidToolJson(request, intent, "", raw,
                    "Nie moglem bezpiecznie przygotowac poprawnego wywolania narzedzia.");
        }
    }

    private ToolAction retryContextualToolDecision(ToolCallingRequest request, String previousAnswer, int step) {
        String raw = selectProvider(request).chat(request.brain(), contextualDecisionRetryPrompt(request, previousAnswer, step),
                AIJobType.BACKGROUND).response();
        try {
            return parse(raw);
        } catch (RuntimeException exception) {
            LOGGER.warn("[TOOL_LOOP] contextual decision retry JSON invalid step={} raw={}", step, abbreviate(raw));
            return noToolAction();
        }
    }

    private ToolAction retryInvalidToolAction(
            ToolCallingRequest request,
            ToolIntent intent,
            ToolAction invalidAction,
            String validationError,
            int step
    ) {
        String prompt = retryInvalidPrompt(request, intent, invalidAction, validationError, step);
        String raw = selectProvider(request).chat(request.brain(), prompt, AIJobType.BACKGROUND).response();
        try {
            return parse(raw);
        } catch (RuntimeException exception) {
            LOGGER.warn("[TOOL_LOOP] invalid-action retry JSON invalid step={} raw={}", step, abbreviate(raw));
            return fallbackAfterInvalidToolJson(request, intent, "", raw,
                    "Nie moglem bezpiecznie przygotowac poprawionego wywolania narzedzia.");
        }
    }

    private ToolAction parse(String raw) {
        try {
            JsonNode node = objectMapper.readTree(extractJsonPayload(raw));
            String type = text(node, "type");
            if ("TOOL_REQUEST".equalsIgnoreCase(type)) {
                throw new ToolException("Model returned main action envelope instead of concrete tool action");
            }
            String action = text(node, "action");
            if (action.isBlank() && !text(node, "name").isBlank()) {
                String name = text(node, "name");
                Map<String, Object> arguments = objectMapper.convertValue(node.path("arguments"), MAP_TYPE);
                return new ToolAction("TOOL_CALL", "knowledge", name, arguments, text(node, "reason"), "");
            }
            if ("FINAL_ANSWER".equalsIgnoreCase(action)) {
                return new ToolAction("FINAL_ANSWER", "", "", Map.of(), "", text(node, "answer"));
            }
            if ("NO_TOOL".equalsIgnoreCase(action) || "NONE".equalsIgnoreCase(action)) {
                return noToolAction();
            }
            if (!"TOOL_CALL".equalsIgnoreCase(action)) {
                throw new ToolException("Unsupported tool loop action: " + action);
            }
            Map<String, Object> arguments = objectMapper.convertValue(node.path("arguments"), MAP_TYPE);
            return new ToolAction(action, text(node, "tool"), text(node, "operation"), arguments, text(node, "reason"), "");
        } catch (JsonProcessingException exception) {
            throw new ToolException("Invalid tool action JSON", exception);
        }
    }

    private ToolAction fallbackAfterInvalidToolJson(
            ToolCallingRequest request,
            ToolIntent intent,
            String observation,
            String raw,
            String fallback
    ) {
        ToolAction webContinuation = webContinuationAfterUnsafeStep(request, intent, observation, raw,
                "Main model requested current external information but returned an unsafe web continuation.");
        if (webContinuation != null) {
            return webContinuation;
        }
        return safePlainTextFinalAnswer(raw, fallback);
    }

    private ToolAction webContinuationAfterUnsafeStep(
            ToolCallingRequest request,
            ToolIntent intent,
            String observation,
            String raw,
            String searchReason
    ) {
        if (intent == ToolIntent.SEARCH_WEB) {
            ToolAction rawUrlAction = readUrlAction(firstHttpUrl(raw),
                    "Model requested browsing a specific URL; WebSearchTool reads pages through READ_WEB_PAGE.");
            if (rawUrlAction != null) {
                LOGGER.warn("[TOOL_LOOP] coercing malformed web step into web.READ_WEB_PAGE from raw URL requestId={}",
                        request.requestId());
                return rawUrlAction;
            }
            ToolAction readAction = readAcceptedWebResultAction(observation);
            if (readAction != null) {
                LOGGER.warn("[TOOL_LOOP] coercing malformed web step into web.READ_WEB_PAGE from previous search result requestId={}",
                        request.requestId());
                return readAction;
            }
            if (shouldCoerceWebToolRequest(raw)) {
                LOGGER.warn("[TOOL_LOOP] coercing repeated TOOL_REQUEST envelope into web.SEARCH_WEB requestId={}",
                        request.requestId());
                return webSearchAction(request, searchReason, raw);
            }
        }
        return null;
    }

    private ToolAction initialWebAction(ToolCallingRequest request) {
        ToolAction urlAction = readUrlAction(firstHttpUrl(safe(request.goal()) + " " + safe(request.userMessage())),
                "Main model requested current information from a specific URL. WebSearchTool reads the page directly.");
        if (urlAction != null) {
            return urlAction;
        }
        return webSearchAction(request,
                "Main model requested current external information. WebSearchTool is the configured read-only web capability.");
    }

    private ToolAction webSearchAction(ToolCallingRequest request, String reason) {
        return webSearchAction(request, reason, "");
    }

    private ToolAction webSearchAction(ToolCallingRequest request, String reason, String rawEnvelope) {
        String query = webSearchQuery(request, rawEnvelope);
        return new ToolAction("TOOL_CALL", "web", "SEARCH_WEB", Map.of(
                "query", query,
                "maxResults", 5
        ), reason, "");
    }

    private ToolAction readAcceptedWebResultAction(String observation) {
        String url = firstAcceptedWebUrl(observation);
        return readUrlAction(url, "Search snippets were relevant but incomplete, so the best result page must be read.");
    }

    private ToolAction readUrlAction(String url, String reason) {
        if (url.isBlank()) {
            return null;
        }
        return new ToolAction("TOOL_CALL", "web", "READ_WEB_PAGE", Map.of(
                "url", url
        ), reason, "");
    }

    private String webSearchQuery(ToolCallingRequest request, String rawEnvelope) {
        String envelopeGoal = toolRequestEnvelopeGoal(rawEnvelope);
        String source = !envelopeGoal.isBlank() ? envelopeGoal : safe(request.goal());
        if (source.isBlank()) {
            source = safe(request.userMessage());
        }
        String text = (source + " " + safe(request.userMessage())).strip();
        String entityQuery = entityFocusedSearchQuery(text);
        if (!entityQuery.isBlank()) {
            return entityQuery;
        }
        String cleaned = cleanupSearchQuery(source);
        if (cleaned.isBlank()) {
            cleaned = cleanupSearchQuery(request.userMessage());
        }
        return cleaned.isBlank() ? safe(request.userMessage()).strip() : cleaned;
    }

    private String entityFocusedSearchQuery(String text) {
        String normalized = safe(text).replace('-', ' ');
        Set<String> parts = new LinkedHashSet<>();
        Matcher gpu = Pattern.compile("(?i)\\b(rtx|gtx|rx)\\s*(\\d{3,4})\\s*(ti|super|xt)?\\b").matcher(normalized);
        while (gpu.find()) {
            addQueryPart(parts, (gpu.group(1) + " " + gpu.group(2) + " " + safe(gpu.group(3))).strip().replaceAll("\\s+", " "));
            addAdjacentProductTerm(parts, normalized.substring(gpu.end()));
        }
        Matcher memory = Pattern.compile("(?i)\\b(\\d{1,3})\\s*(gb|gib)\\b").matcher(normalized);
        while (memory.find()) {
            addQueryPart(parts, memory.group(1) + "GB");
        }
        String lower = normalizeAscii(text);
        if (!parts.isEmpty()) {
            if (lower.matches(".*\\b(cena|ceny|koszt|kosztuje|price|prices|market)\\b.*")) {
                addQueryPart(parts, "cena");
            }
            if (lower.matches(".*\\b(uzywan|used|wtorn|secondary|marketplace)\\w*\\b.*")) {
                addQueryPart(parts, "uzywana");
            }
            if (lower.matches(".*\\b(polska|poland|pln|allegro|olx|ebay)\\b.*")) {
                addQueryPart(parts, "Polska");
            }
            if (lower.contains("allegro")) {
                addQueryPart(parts, "Allegro");
            }
            if (lower.contains("olx")) {
                addQueryPart(parts, "OLX");
            }
            if (parts.stream().noneMatch(part -> part.equalsIgnoreCase("Allegro") || part.equalsIgnoreCase("OLX"))
                    && lower.matches(".*\\b(uzywan|used|wtorn|secondary|marketplace|cena|ceny|price|prices)\\w*\\b.*")) {
                addQueryPart(parts, "Allegro");
                addQueryPart(parts, "OLX");
            }
            return String.join(" ", parts);
        }
        return "";
    }

    private void addQueryPart(Set<String> parts, String value) {
        String candidate = safe(value).strip();
        if (candidate.isBlank()) {
            return;
        }
        String normalizedCandidate = normalizeAscii(candidate).replaceAll("\\s+", " ");
        boolean exists = parts.stream()
                .map(part -> normalizeAscii(part).replaceAll("\\s+", " "))
                .anyMatch(normalizedCandidate::equals);
        if (!exists) {
            parts.add(candidate);
        }
    }

    private void addAdjacentProductTerm(Set<String> parts, String textAfterMatch) {
        Matcher matcher = Pattern.compile("(?i)^\\s*([a-z0-9][a-z0-9-]{2,})\\b").matcher(safe(textAfterMatch));
        if (!matcher.find()) {
            return;
        }
        String term = matcher.group(1);
        String normalized = normalizeAscii(term);
        if (Set.of("gpu", "gpus", "card", "cards", "karta", "karty", "used", "uzywana", "cena", "price").contains(normalized)) {
            return;
        }
        addQueryPart(parts, term);
    }

    private String cleanupSearchQuery(String value) {
        String query = safe(value)
                .replaceAll("(?i)\\b(search|retrieve|find|look up|provide|get)\\b", " ")
                .replaceAll("(?i)\\b(the live web|live web|web|internet)\\b", " ")
                .replaceAll("(?i)\\b(siema|siemka|hej|czesc|ponownie|prosze)\\b", " ")
                .replaceAll("(?i)\\b(czy teraz|jestes w stanie|mi jakas|podac)\\b", " ")
                .replaceAll("[\"'{}\\[\\]():;,?!.]+", " ")
                .replaceAll("\\s+", " ")
                .strip();
        return query.length() <= 160 ? query : query.substring(0, 160).strip();
    }

    private String toolRequestEnvelopeGoal(String raw) {
        if (!looksLikeToolRequestEnvelope(raw)) {
            return "";
        }
        try {
            JsonNode node = objectMapper.readTree(extractJsonPayload(raw));
            return text(node, "goal");
        } catch (JsonProcessingException | RuntimeException exception) {
            return "";
        }
    }

    private String firstAcceptedWebUrl(String observation) {
        if (observation == null || observation.isBlank()) {
            return "";
        }
        try {
            JsonNode root = objectMapper.readTree(extractJsonPayload(observation));
            JsonNode data = root.path("data");
            String acceptedUrl = firstUrl(data.path("acceptedResults"));
            if (!acceptedUrl.isBlank()) {
                return acceptedUrl;
            }
            return firstUrl(data.path("results"));
        } catch (JsonProcessingException | RuntimeException exception) {
            return "";
        }
    }

    private String firstUrl(JsonNode results) {
        if (!results.isArray()) {
            return "";
        }
        for (JsonNode item : results) {
            String url = text(item, "url");
            if (url.startsWith("http://") || url.startsWith("https://")) {
                return url;
            }
        }
        return "";
    }

    private String firstHttpUrl(String value) {
        Matcher matcher = Pattern.compile("https?://[^\\s\"'<>)}\\]]+").matcher(safe(value));
        if (!matcher.find()) {
            return "";
        }
        return matcher.group()
                .replaceAll("[.,;!?]+$", "")
                .strip();
    }

    private ToolAction safePlainTextFinalAnswer(String raw, String fallback) {
        return plainTextFinalAnswer(looksLikeStructuredEnvelope(raw) ? "" : raw, fallback);
    }

    private ToolAction plainTextFinalAnswer(String raw, String fallback) {
        String answer = raw == null || raw.isBlank() ? fallback : raw.strip();
        return new ToolAction("FINAL_ANSWER", "", "", Map.of(), "", answer);
    }

    private ToolAction noToolAction() {
        return new ToolAction("NO_TOOL", "", "", Map.of(), "", "");
    }

    private void validate(ToolAction action) {
        ToolDefinition tool = toolRegistry.definitions().stream()
                .filter(definition -> definition.name().equals(action.tool()))
                .findFirst()
                .orElseThrow(() -> new ToolException("Unknown tool: " + action.tool()));
        ToolOperationDefinition operation = tool.operations().stream()
                .filter(definition -> definition.name().equals(action.operation()))
                .findFirst()
                .orElseThrow(() -> new ToolException("Unsupported operation: " + action.operation()));
        for (ToolArgumentDefinition argument : operation.arguments()) {
            if (argument.required() && !action.arguments().containsKey(argument.name())) {
                throw new ToolException("Missing required argument: " + argument.name());
            }
        }
    }

    private ToolAction normalizeAction(ToolAction action) {
        if (!"TOOL_CALL".equalsIgnoreCase(action.action())) {
            return action;
        }
        Map<String, Object> arguments = new HashMap<>(action.arguments());
        Object path = arguments.get("path");
        if (path != null) {
            arguments.put("path", String.valueOf(path).replace('\\', '/'));
        }
        return new ToolAction(
                action.action(),
                action.tool(),
                action.operation().toUpperCase(Locale.ROOT),
                arguments,
                action.reason(),
                action.answer()
        );
    }

    private boolean isNoTool(ToolAction action) {
        return "NO_TOOL".equalsIgnoreCase(action.action()) || "NONE".equalsIgnoreCase(action.action());
    }

    private boolean isTerminalWrite(String operation) {
        return "CREATE_DOCUMENT".equalsIgnoreCase(operation)
                || "UPDATE_DOCUMENT".equalsIgnoreCase(operation)
                || "APPEND_DOCUMENT".equalsIgnoreCase(operation)
                || "DELETE_DOCUMENT".equalsIgnoreCase(operation)
                || "MOVE_DOCUMENT".equalsIgnoreCase(operation)
                || "RENAME_DOCUMENT".equalsIgnoreCase(operation)
                || "CREATE_FOLDER".equalsIgnoreCase(operation)
                || "DELETE_FOLDER".equalsIgnoreCase(operation)
                || "MOVE_FOLDER".equalsIgnoreCase(operation);
    }

    private String generateFinalAnswer(ToolCallingRequest request, ToolAction action, ToolResult result, boolean waitingApproval) {
        publish(request, CognitiveEventType.FINAL_ANSWER_GENERATION_STARTED, "STARTED", "LLM final answer generation started",
                targetNode(action), 0, Map.of("tool", action.tool(), "operation", action.operation()));
        String prompt = finalAnswerPrompt(request, action, result, waitingApproval);
        String answer = selectProvider(request).chat(request.brain(), prompt, AIJobType.BACKGROUND).response();
        publish(request, CognitiveEventType.FINAL_ANSWER_GENERATION_FINISHED, "FINISHED", "LLM final answer generation finished",
                targetNode(action), 0, Map.of("tool", action.tool(), "operation", action.operation(), "characters", answer.length()));
        return answer == null || answer.isBlank() ? fallbackFinalAnswer(action, result, waitingApproval) : answer.strip();
    }

    private String finalAnswerPrompt(ToolCallingRequest request, ToolAction action, ToolResult result, boolean waitingApproval) {
        return request.basePrompt()
                + "\n\nYou just used a tool. Now write the final user-facing answer."
                + "\nDo not reveal hidden chain-of-thought. You may briefly mention what was done."
                + "\nIf approval is required, clearly tell the user that a draft is waiting for approval."
                + "\nKeep the answer concise and natural, in the user's language."
                + "\n\nUser request:\n" + request.userMessage()
                + "\n\nTool call chosen by you:\n" + toolActionJson(action)
                + "\n\nTool result:\n" + observation(result)
                + "\n\nApproval required: " + waitingApproval
                + "\nReturn plain text only.";
    }

    private String fallbackFinalAnswer(ToolAction action, ToolResult result, boolean waitingApproval) {
        String path = String.valueOf(result.data().getOrDefault("path", action.arguments().getOrDefault("path", "")));
        if (waitingApproval) {
            return "Przygotowalem szkic zmiany" + (path.isBlank() ? "" : " w " + path) + ". Czeka na zatwierdzenie.";
        }
        if (path.isBlank()) {
            return result.message().isBlank() ? "Wykonalem operacje narzedziowa." : result.message();
        }
        return "Gotowe. Operacja zostala wykonana dla: " + path + ".";
    }

    private String toolActionJson(ToolAction action) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "tool", action.tool(),
                    "operation", action.operation(),
                    "arguments", action.arguments(),
                    "reason", safe(action.reason())
            ));
        } catch (JsonProcessingException exception) {
            return action.operation();
        }
    }

    private String prompt(ToolCallingRequest request, ToolIntent intent, String observation, int step) {
        return request.basePrompt()
                + "\n\n" + toolRegistry.promptSection()
                + "\n\nTOOL ACTION JSON EXAMPLES\n"
                + "No tool needed:\n"
                + "{\"action\":\"NO_TOOL\",\"reason\":\"The user is only chatting and no tool is useful.\"}\n"
                + "Create a knowledge document:\n"
                + "{\"action\":\"TOOL_CALL\",\"tool\":\"knowledge\",\"operation\":\"CREATE_DOCUMENT\",\"arguments\":{\"path\":\"People/Kuba.md\",\"content\":\"# Kuba\\n\\n## Urodziny\\n\\n6 czerwca\\n\"},\"reason\":\"User asked to save a birthday fact.\"}\n"
                + "Update an existing document:\n"
                + "{\"action\":\"TOOL_CALL\",\"tool\":\"knowledge\",\"operation\":\"UPDATE_DOCUMENT\",\"arguments\":{\"path\":\"People/Kuba.md\",\"instruction\":\"SET_SECTION:Urodziny\",\"text\":\"6 czerwca\"},\"reason\":\"User asked to update the birthday section.\"}\n"
                + "Search knowledge:\n"
                + "{\"action\":\"TOOL_CALL\",\"tool\":\"knowledge\",\"operation\":\"SEARCH_CONTENT\",\"arguments\":{\"query\":\"Kuba urodziny\"},\"reason\":\"Need to inspect existing knowledge before answering.\"}\n"
                + "Search the web through local SearXNG:\n"
                + "{\"action\":\"TOOL_CALL\",\"tool\":\"web\",\"operation\":\"SEARCH_WEB\",\"arguments\":{\"query\":\"RTX 4060 Ti 16GB cena Polska\",\"maxResults\":5},\"reason\":\"The user asked for current internet information.\"}\n"
                + "Read a web search result page:\n"
                + "{\"action\":\"TOOL_CALL\",\"tool\":\"web\",\"operation\":\"READ_WEB_PAGE\",\"arguments\":{\"url\":\"https://example.com/result\"},\"reason\":\"Search result snippets did not contain the needed price/details.\"}\n"
                + "\n\nDetected tool intent: " + intent
                + "\nThis detected intent is only a weak hint from Java. You own the final tool decision."
                + "\nMain model tool goal:\n" + safe(request.goal())
                + "\nMain model reason summary:\n" + safe(request.reason())
                + "\nUser request:\n" + request.userMessage()
                + "\n\nPrevious tool observation:\n" + observation
                + "\n\nStep: " + step
                + "\nYou are not writing the normal assistant answer in this stage."
                + "\nUse NO_TOOL when no tool should be used and the normal model answer should continue."
                + "\nIf recent conversation context shows the assistant proposed a knowledge update and the latest user confirms it, call KnowledgeTool instead of answering with text."
                + "\nFor web search, inspect previous observations. If sourceQualityAccepted=false, change the query and search again."
                + "\nIf sourceQualityAccepted=false but acceptedResults contains relevant URLs, use web.READ_WEB_PAGE on the best result URLs before asking the user for clarification."
                + "\nUse READ_WEB_PAGE when search snippets do not contain prices, dates, exact values, or enough evidence."
                + "\nYou may perform multiple web searches when results are weak, irrelevant, or from the wrong domain."
                + "\nYou may read multiple web pages when comparing offers or looking for a concrete listing."
                + "\nIf a READ_WEB_PAGE observation has pageQualityAccepted=false, do not finish. Search again with a different query or read another relevant result."
                + "\nFor requested sites/domains, include them in the query, e.g. site:allegro.pl RTX 4060 Ti."
                + "\nOnly return FINAL_ANSWER when observations contain enough relevant evidence, or when you honestly cannot find it after attempts."
                + "\nThe LLM is the only component allowed to decide semantic knowledge writes."
                + "\nJava will only validate and execute your structured TOOL_CALL."
                + "\nReturn JSON only. Choose TOOL_CALL, NO_TOOL, or FINAL_ANSWER only after tool observations.";
    }

    private String repairPrompt(String raw) {
        return "Repair this malformed tool action into valid JSON only. It must be either "
                + "{\"action\":\"TOOL_CALL\",\"tool\":\"knowledge\",\"operation\":\"SEARCH_CONTENT\",\"arguments\":{\"query\":\"...\"},\"reason\":\"...\"} "
                + "or {\"action\":\"TOOL_CALL\",\"tool\":\"web\",\"operation\":\"SEARCH_WEB\",\"arguments\":{\"query\":\"...\",\"maxResults\":5},\"reason\":\"...\"} "
                + "or {\"action\":\"TOOL_CALL\",\"tool\":\"web\",\"operation\":\"READ_WEB_PAGE\",\"arguments\":{\"url\":\"https://...\"},\"reason\":\"...\"} "
                + "or {\"action\":\"TOOL_CALL\",\"tool\":\"knowledge\",\"operation\":\"CREATE_DOCUMENT\",\"arguments\":{\"path\":\"...\",\"content\":\"...\"},\"reason\":\"...\"} "
                + "or {\"action\":\"TOOL_CALL\",\"tool\":\"knowledge\",\"operation\":\"UPDATE_DOCUMENT\",\"arguments\":{\"path\":\"...\",\"instruction\":\"...\",\"text\":\"...\"},\"reason\":\"...\"} "
                + "or {\"action\":\"NO_TOOL\",\"reason\":\"...\"} "
                + "or {\"action\":\"FINAL_ANSWER\",\"answer\":\"...\"}.\nMalformed:\n" + abbreviate(raw);
    }

    private String contextualDecisionRetryPrompt(ToolCallingRequest request, String previousAnswer, int step) {
        return request.basePrompt()
                + "\n\n" + toolRegistry.promptSection()
                + "\n\nYou returned a normal answer during the tool-decision stage:\n" + safe(previousAnswer)
                + "\n\nRe-evaluate using the full recent conversation context and the latest user message."
                + "\nIf the latest user message confirms a previous assistant proposal to create, update, append, move, rename, or delete knowledge, return the exact TOOL_CALL now."
                + "\nIf no tool is useful, return {\"action\":\"NO_TOOL\",\"reason\":\"...\"}."
                + "\nDo not write the user-facing answer here."
                + "\nJava must not choose destination, content, or operation for you."
                + "\nReturn JSON only. Step: " + step;
    }

    private String retryPrompt(ToolCallingRequest request, ToolIntent intent, String previousAnswer, int step) {
        return request.basePrompt()
                + "\n\n" + toolRegistry.promptSection()
                + "\n\nThe previous response did not use a tool:\n" + safe(previousAnswer)
                + "\n\nThe user request is:\n" + request.userMessage()
                + "\n\nDetected intent: " + intent
                + "\nIf the user asks to save/create/update knowledge and provided enough information, return a TOOL_CALL."
                + "\nIf the user asks for current web information, prices, news, releases, or external source-backed data, return web.SEARCH_WEB."
                + "\nFor a new saved fact, CREATE_DOCUMENT is supported. You must decide path and markdown content."
                + "\nExample JSON only:\n"
                + "{\"action\":\"TOOL_CALL\",\"tool\":\"knowledge\",\"operation\":\"CREATE_DOCUMENT\",\"arguments\":{\"path\":\"People/Kuba.md\",\"content\":\"# Kuba\\n\\n## Urodziny\\n\\n6 czerwca\\n\"},\"reason\":\"User asked to save a birthday fact.\"}"
                + "\nIf a tool is truly inappropriate, return NO_TOOL. Step: " + step;
    }

    private String retryInvalidPrompt(
            ToolCallingRequest request,
            ToolIntent intent,
            ToolAction invalidAction,
            String validationError,
            int step
    ) {
        return request.basePrompt()
                + "\n\n" + toolRegistry.promptSection()
                + "\n\nYour previous tool action was invalid."
                + "\nValidation error: " + validationError
                + "\nInvalid operation: " + invalidAction.operation()
                + "\nUser request:\n" + request.userMessage()
                + "\nDetected intent: " + intent
                + "\nReturn corrected JSON only. Use one of the supported operations listed above."
                + "\nFor saving new knowledge, use CREATE_DOCUMENT with path and content. Step: " + step;
    }

    private boolean requiresToolAttempt(ToolIntent intent) {
        return intent == ToolIntent.CREATE_DOCUMENT
                || intent == ToolIntent.SAVE_KNOWLEDGE
                || intent == ToolIntent.UPDATE_DOCUMENT
                || intent == ToolIntent.APPEND_DOCUMENT
                || intent == ToolIntent.DELETE_KNOWLEDGE
                || intent == ToolIntent.ORGANIZE_KNOWLEDGE
                || intent == ToolIntent.SEARCH_WEB;
    }

    private ToolIntent resolveIntent(ToolCallingRequest request) {
        ToolIntent messageIntent = intentDetector.detect(request.userMessage());
        if (messageIntent != ToolIntent.NO_TOOL) {
            return messageIntent;
        }
        ToolIntent contextualIntent = intentDetector.detect(safe(request.goal()) + " " + safe(request.reason()));
        if (contextualIntent != ToolIntent.NO_TOOL) {
            LOGGER.info("[TOOL_LOOP] resolved intent from main model tool request requestId={} intent={}",
                    request.requestId(), contextualIntent);
            return contextualIntent;
        }
        String context = normalizeAscii(safe(request.goal()) + " " + safe(request.reason()));
        if (!context.isBlank()
                && context.matches(".*\\b(web|internet|external|current|live|market|price|prices|listing|listings|search)\\b.*")) {
            LOGGER.info("[TOOL_LOOP] inferred web intent from main model tool request requestId={}", request.requestId());
            return ToolIntent.SEARCH_WEB;
        }
        return messageIntent;
    }

    private String observation(ToolResult result) {
        try {
            return "TOOL OBSERVATION\n" + objectMapper.writeValueAsString(Map.of(
                    "tool", result.tool(),
                    "operation", result.operation(),
                    "success", result.success(),
                    "requiresApproval", result.requiresApproval(),
                    "draftId", result.draftId(),
                    "message", result.message(),
                    "errorCode", result.errorCode(),
                    "errorMessage", result.errorMessage(),
                    "data", result.data()
            ));
        } catch (JsonProcessingException exception) {
            return "TOOL OBSERVATION unavailable";
        }
    }

    private ToolResult enrichWebSearchQuality(ToolCallingRequest request, ToolResult result, int step) {
        WebSearchQualityReport report = webSearchQualityEvaluator.evaluate(request, result);
        Map<String, Object> data = new HashMap<>(result.data());
        data.put("sourceQualityAccepted", report.accepted());
        data.put("sourceQualityScore", report.score());
        data.put("sourceQualityReason", report.reason());
        data.put("acceptedResults", report.acceptedResults());
        publish(request,
                report.accepted() ? CognitiveEventType.TOOL_VERIFICATION_FINISHED : CognitiveEventType.TOOL_VERIFICATION_STARTED,
                report.accepted() ? "VERIFIED" : "RETRY_NEEDED",
                report.accepted() ? "Web search quality accepted" : "Web search quality rejected",
                "web:search",
                step,
                Map.of(
                        "tool", result.tool(),
                        "operation", result.operation(),
                        "sourceQualityAccepted", report.accepted(),
                        "sourceQualityScore", report.score(),
                        "sourceQualityReason", report.reason(),
                        "acceptedResults", report.acceptedResults().size()
                ));
        return new ToolResult(
                result.success(),
                result.tool(),
                result.operation(),
                result.requestId(),
                result.conversationId(),
                result.changed(),
                result.targetNodeIds(),
                result.message(),
                data,
                result.errorCode(),
                result.errorMessage(),
                result.requiresApproval(),
                result.draftId()
        );
    }

    private ToolResult enrichWebPageQuality(ToolCallingRequest request, ToolResult result, int step, ToolAction action) {
        String content = safe(String.valueOf(result.data().getOrDefault("content", "")));
        boolean requiresValue = requiresSpecificValue(request.userMessage() + " " + request.goal() + " " + request.reason());
        boolean valueFound = containsSpecificValue(content);
        boolean accepted = result.success() && !content.isBlank() && (!requiresValue || valueFound);
        String reason;
        if (accepted) {
            reason = "Web page contains enough visible content for the requested answer.";
        } else if (content.isBlank()) {
            reason = "Web page read returned no visible content. Search again or read another result.";
        } else if (requiresValue) {
            reason = "Web page was readable, but no requested numeric value was found. Search again or read another result.";
        } else {
            reason = "Web page content was not sufficient. Search again or read another result.";
        }
        Map<String, Object> data = new HashMap<>(result.data());
        data.put("pageQualityAccepted", accepted);
        data.put("pageQualityReason", reason);
        data.put("pageValueFound", valueFound);
        data.put("requiresSpecificValue", requiresValue);
        publish(request,
                accepted ? CognitiveEventType.TOOL_VERIFICATION_FINISHED : CognitiveEventType.TOOL_VERIFICATION_STARTED,
                accepted ? "VERIFIED" : "RETRY_NEEDED",
                accepted ? "Tool result verified" : "Web page did not contain enough evidence",
                targetNode(action),
                step,
                Map.of(
                        "tool", result.tool(),
                        "operation", result.operation(),
                        "pageQualityAccepted", accepted,
                        "pageQualityReason", reason,
                        "pageValueFound", valueFound,
                        "requiresSpecificValue", requiresValue
                ));
        return new ToolResult(
                result.success(),
                result.tool(),
                result.operation(),
                result.requestId(),
                result.conversationId(),
                result.changed(),
                result.targetNodeIds(),
                result.message(),
                data,
                result.errorCode(),
                result.errorMessage(),
                result.requiresApproval(),
                result.draftId()
        );
    }

    private boolean webSearchAccepted(ToolResult result) {
        Object accepted = result.data().get("sourceQualityAccepted");
        return !(accepted instanceof Boolean value) || value;
    }

    private boolean webPageAccepted(ToolResult result) {
        Object accepted = result.data().get("pageQualityAccepted");
        return !(accepted instanceof Boolean value) || value;
    }

    private boolean requiresSpecificValue(String text) {
        String normalized = normalizeAscii(text);
        return normalized.matches(".*\\b(cena|ceny|koszt|kosztuje|kurs|notowania|price|prices|rate|market)\\b.*");
    }

    private boolean containsSpecificValue(String text) {
        return SPECIFIC_VALUE_PATTERN.matcher(safe(text)).find();
    }

    private Map<String, Object> resultMetadata(ToolResult result) {
        Map<String, Object> values = new HashMap<>();
        values.put("tool", result.tool());
        values.put("operation", result.operation());
        values.put("success", result.success());
        values.put("changed", result.changed());
        values.put("requiresApproval", result.requiresApproval());
        values.put("draftId", result.draftId());
        values.put("targetNodeIds", result.targetNodeIds());
        values.put("message", result.message());
        values.put("errorCode", result.errorCode());
        values.put("errorMessage", result.errorMessage());
        if (result.data().containsKey("baseUrl")) {
            values.put("baseUrl", result.data().get("baseUrl"));
        }
        if (result.data().containsKey("query")) {
            values.put("query", result.data().get("query"));
        }
        if (result.data().containsKey("url")) {
            values.put("url", result.data().get("url"));
        }
        if (result.data().containsKey("title")) {
            values.put("title", result.data().get("title"));
        }
        if (result.data().containsKey("statusCode")) {
            values.put("statusCode", result.data().get("statusCode"));
        }
        if (result.data().containsKey("contentType")) {
            values.put("contentType", result.data().get("contentType"));
        }
        if (result.data().containsKey("sourceQualityAccepted")) {
            values.put("sourceQualityAccepted", result.data().get("sourceQualityAccepted"));
            values.put("sourceQualityScore", result.data().getOrDefault("sourceQualityScore", 0.0d));
            values.put("sourceQualityReason", result.data().getOrDefault("sourceQualityReason", ""));
        }
        if (result.data().containsKey("pageQualityAccepted")) {
            values.put("pageQualityAccepted", result.data().get("pageQualityAccepted"));
            values.put("pageQualityReason", result.data().getOrDefault("pageQualityReason", ""));
            values.put("pageValueFound", result.data().getOrDefault("pageValueFound", false));
        }
        return values;
    }

    private boolean isTerminalFailedSearch(ToolAction action) {
        return "web".equalsIgnoreCase(action.tool()) && "SEARCH_WEB".equalsIgnoreCase(action.operation());
    }

    private boolean isTerminalWebSearch(ToolAction action) {
        return "web".equalsIgnoreCase(action.tool()) && "SEARCH_WEB".equalsIgnoreCase(action.operation());
    }

    private boolean isWebPageRead(ToolAction action) {
        return "web".equalsIgnoreCase(action.tool()) && "READ_WEB_PAGE".equalsIgnoreCase(action.operation());
    }

    private Map<String, Object> actionMetadata(ToolAction action) {
        Map<String, Object> values = new HashMap<>();
        values.put("tool", action.tool());
        values.put("operation", action.operation());
        values.put("reason", safe(action.reason()));
        values.put("arguments", action.arguments());
        Object path = action.arguments().get("path");
        Object query = action.arguments().get("query");
        Object content = action.arguments().get("content");
        Object text = action.arguments().get("text");
        if (path != null) {
            values.put("path", String.valueOf(path));
            values.put("targetPath", String.valueOf(path));
        }
        if (query != null) {
            values.put("query", String.valueOf(query));
        }
        String preview = content == null ? String.valueOf(text == null ? "" : text) : String.valueOf(content);
        if (!preview.isBlank()) {
            values.put("contentPreview", abbreviate(preview));
        }
        return values;
    }

    private void saveDebug(ToolCallingRequest request, ToolIntent intent, List<ToolRuntimeStep> steps, String status, List<String> errors) {
        debugService.save(new ToolRuntimeSnapshot(request.requestId(), request.conversationId(), intent, steps, status, errors));
    }

    private void publish(
            ToolCallingRequest request,
            CognitiveEventType event,
            String status,
            String message,
            String nodeId,
            int step,
            Map<String, Object> metadata
    ) {
        Map<String, Object> values = new HashMap<>(metadata == null ? Map.of() : metadata);
        values.put("requestId", request.requestId());
        values.put("conversationId", request.conversationId());
        values.put("stepNumber", step);
        values.put("timestamp", Instant.now().toString());
        cognitiveEventBus.publish(event, status, message, nodeId, values);
    }

    private AIProvider selectProvider(ToolCallingRequest request) {
        return aiProviders.stream()
                .filter(provider -> provider.provider().equalsIgnoreCase(request.brain().provider()))
                .findFirst()
                .orElseThrow(() -> new AIProviderException("AI provider is not available: " + request.brain().provider()));
    }

    private String targetNode(ToolAction action) {
        Object path = action.arguments().get("path");
        if (path == null) {
            Object query = action.arguments().get("query");
            if ("web".equalsIgnoreCase(action.tool()) && query != null) {
                return "web:search";
            }
            Object url = action.arguments().get("url");
            if ("web".equalsIgnoreCase(action.tool()) && url != null) {
                return "web:" + Integer.toHexString(String.valueOf(url).hashCode());
            }
            return null;
        }
        return "knowledge-document:" + String.valueOf(path).replace('\\', '/');
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText();
    }

    private String stripFences(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.startsWith("```")) {
            value = value.replaceFirst("^```[a-zA-Z]*", "").replaceFirst("```$", "").trim();
        }
        return value;
    }

    private String extractJsonPayload(String raw) {
        String value = stripFences(raw);
        if (value.startsWith("{")) {
            return value;
        }
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return value.substring(start, end + 1);
        }
        return value;
    }

    private boolean looksLikeToolRequestEnvelope(String raw) {
        String value = raw == null ? "" : raw;
        return value.contains("\"type\"") && value.contains("\"TOOL_REQUEST\"");
    }

    private boolean shouldCoerceWebToolRequest(String raw) {
        String value = normalizeAscii(raw);
        return looksLikeToolRequestEnvelope(raw)
                || value.contains("tool_request")
                || value.contains("web_browse")
                || value.contains("web browse")
                || value.contains("browse tool")
                || value.contains("open the url")
                || value.contains("read the url")
                || value.contains("retrieve page")
                || value.contains("fetch price from");
    }

    private boolean looksLikeStructuredEnvelope(String raw) {
        String value = raw == null ? "" : raw;
        return value.contains("\"type\"")
                && (value.contains("\"TOOL_REQUEST\"")
                || value.contains("\"FINAL_ANSWER\"")
                || value.contains("\"CLARIFICATION\""));
    }

    private String abbreviate(String value) {
        String safe = value == null ? "" : value.replace('\n', ' ');
        return safe.length() <= 600 ? safe : safe.substring(0, 600);
    }

    private String normalizeAscii(String value) {
        return java.text.Normalizer.normalize(safe(value), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
