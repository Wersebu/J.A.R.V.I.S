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
import java.util.List;
import java.util.Locale;
import java.util.Map;

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

    private final List<AIProvider> aiProviders;
    private final ToolManager toolManager;
    private final ToolRegistry toolRegistry;
    private final ToolIntentDetector intentDetector;
    private final ToolRuntimeProperties properties;
    private final CognitiveEventBus cognitiveEventBus;
    private final ToolRuntimeDebugService debugService;
    private final ObjectMapper objectMapper;

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
    }

    @Override
    public ToolCallingResult execute(ToolCallingRequest request) {
        ToolIntent intent = intentDetector.detect(request.userMessage());
        if (!properties.isEnabled() || intent == ToolIntent.NO_TOOL) {
            return new ToolCallingResult(false, "", List.of(), List.of());
        }

        Instant started = Instant.now();
        List<ToolRuntimeStep> steps = new ArrayList<>();
        List<ToolResult> results = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int maxCalls = request.knowledgeMode() == KnowledgeMode.RESEARCH
                ? properties.maxCallsResearch()
                : properties.maxCallsFast();
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

                publish(request, CognitiveEventType.TOOL_SELECTION_STARTED, "SELECTING", "Asking LLM for next tool action",
                        null, step, Map.of("decisionOwner", "LLM"));
                ToolAction action = normalizeAction(nextAction(request, intent, observation, step));
                if ("FINAL_ANSWER".equalsIgnoreCase(action.action())) {
                    steps.add(new ToolRuntimeStep(step, "FINAL_ANSWER", "", "", "FINISHED", null));
                    saveDebug(request, intent, steps, "FINISHED", errors);
                    publish(request, CognitiveEventType.TOOL_LOOP_FINISHED, "FINISHED", "Tool loop finished", null, step, Map.of());
                    return new ToolCallingResult(true, action.answer(), steps, results);
                }

                publish(request, CognitiveEventType.TOOL_CALL_PROPOSED, "PROPOSED", "LLM proposed tool call", null, step,
                        Map.of("tool", action.tool(), "operation", action.operation(), "reason", safe(action.reason())));
                validate(action);
                publish(request, CognitiveEventType.TOOL_CALL_VALIDATED, "VALIDATED", "Tool call validated", null, step,
                        Map.of("tool", action.tool(), "operation", action.operation()));

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
                        targetNode(action), step, Map.of("tool", action.tool(), "operation", action.operation()));
                LOGGER.info("[TOOL_CALL] tool={} operation={} step={}", action.tool(), action.operation(), step);
                ToolResult result = toolManager.execute(toolRequest);
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
                    String answer = draftAnswer(action, result);
                    saveDebug(request, intent, steps, "WAITING_APPROVAL", errors);
                    publish(request, CognitiveEventType.TOOL_LOOP_FINISHED, "WAITING_APPROVAL", "Tool loop waiting for approval",
                            targetNode(action), step, resultMetadata(result));
                    return new ToolCallingResult(true, answer, steps, results);
                }
                if (!result.success()) {
                    failures++;
                    if (failures >= properties.maxConsecutiveFailures()) {
                        errors.add(result.errorMessage().isBlank() ? "Tool execution failed" : result.errorMessage());
                        break;
                    }
                } else {
                    failures = 0;
                }
                observation = observation(result);

                publish(request, CognitiveEventType.TOOL_VERIFICATION_STARTED, "VERIFYING", "Verifying tool result",
                        targetNode(action), step, Map.of("operation", action.operation()));
                publish(request, CognitiveEventType.TOOL_VERIFICATION_FINISHED, "VERIFIED", "Tool result verified",
                        targetNode(action), step, Map.of("operation", action.operation(), "success", result.success()));
                if (result.success() && isTerminalWrite(action.operation())) {
                    String answer = finalToolAnswer(action, result);
                    saveDebug(request, intent, steps, "FINISHED", errors);
                    publish(request, CognitiveEventType.TOOL_LOOP_FINISHED, "FINISHED", "Tool loop finished",
                            targetNode(action), step, resultMetadata(result));
                    return new ToolCallingResult(true, answer, steps, results);
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
            return parse(repaired);
        }
    }

    private ToolAction parse(String raw) {
        try {
            JsonNode node = objectMapper.readTree(stripFences(raw));
            String action = text(node, "action");
            if (action.isBlank() && !text(node, "name").isBlank()) {
                String name = text(node, "name");
                Map<String, Object> arguments = objectMapper.convertValue(node.path("arguments"), MAP_TYPE);
                return new ToolAction("TOOL_CALL", "knowledge", name, arguments, text(node, "reason"), "");
            }
            if ("FINAL_ANSWER".equalsIgnoreCase(action)) {
                return new ToolAction("FINAL_ANSWER", "", "", Map.of(), "", text(node, "answer"));
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

    private String finalToolAnswer(ToolAction action, ToolResult result) {
        String path = String.valueOf(result.data().getOrDefault("path", action.arguments().getOrDefault("path", "")));
        String operation = action.operation();
        if ("CREATE_DOCUMENT".equalsIgnoreCase(operation)) {
            return path.isBlank()
                    ? "Utworzylem nowy dokument wiedzy i zapisalem informacje."
                    : "Utworzylem nowy dokument wiedzy: " + path + ".";
        }
        if ("APPEND_DOCUMENT".equalsIgnoreCase(operation) || "UPDATE_DOCUMENT".equalsIgnoreCase(operation)) {
            return path.isBlank()
                    ? "Zaktualizowalem dokument wiedzy."
                    : "Zaktualizowalem dokument wiedzy: " + path + ".";
        }
        if ("DELETE_DOCUMENT".equalsIgnoreCase(operation)) {
            return path.isBlank() ? "Usunalem dokument wiedzy." : "Usunalem dokument wiedzy: " + path + ".";
        }
        return result.message().isBlank() ? "Wykonalem operacje narzedziowa." : result.message();
    }

    private String draftAnswer(ToolAction action, ToolResult result) {
        String path = String.valueOf(result.data().getOrDefault("path", action.arguments().getOrDefault("path", "")));
        String target = path.isBlank() ? "zmiany w wiedzy" : path;
        String draftId = result.draftId();
        return "Przygotowalem szkic: " + target + ". Zatwierdz go w panelu, aby zapisac zmiany."
                + (draftId.isBlank() ? "" : " Draft: " + draftId + ".");
    }

    private String prompt(ToolCallingRequest request, ToolIntent intent, String observation, int step) {
        return request.basePrompt()
                + "\n\n" + toolRegistry.promptSection()
                + "\n\nDetected tool intent: " + intent
                + "\nUser request:\n" + request.userMessage()
                + "\n\nPrevious tool observation:\n" + observation
                + "\n\nStep: " + step
                + "\nThe LLM is the only component allowed to decide semantic knowledge writes."
                + "\nJava will only validate and execute your structured TOOL_CALL."
                + "\nReturn JSON only. Choose TOOL_CALL or FINAL_ANSWER.";
    }

    private String repairPrompt(String raw) {
        return "Repair this malformed tool action into valid JSON only. It must be either "
                + "{\"action\":\"TOOL_CALL\",\"tool\":\"knowledge\",\"operation\":\"SEARCH_CONTENT\",\"arguments\":{\"query\":\"...\"},\"reason\":\"...\"} "
                + "or {\"action\":\"FINAL_ANSWER\",\"answer\":\"...\"}.\nMalformed:\n" + abbreviate(raw);
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
                    "data", result.data()
            ));
        } catch (JsonProcessingException exception) {
            return "TOOL OBSERVATION unavailable";
        }
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

    private String abbreviate(String value) {
        String safe = value == null ? "" : value.replace('\n', ' ');
        return safe.length() <= 600 ? safe : safe.substring(0, 600);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
