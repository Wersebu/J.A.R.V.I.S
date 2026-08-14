package com.jarvis.memory.pipeline;

import com.jarvis.common.ai.AIProvider;
import com.jarvis.common.ai.AIProviderException;
import com.jarvis.common.ai.AIJobType;
import com.jarvis.common.diagnostics.InferenceDiagnostics;
import com.jarvis.common.diagnostics.InferenceDiagnosticsContext;
import com.jarvis.common.event.CognitiveEventBus;
import com.jarvis.common.event.CognitiveEventType;
import com.jarvis.common.event.GenerationFinishedEvent;
import com.jarvis.common.event.ThinkingTokenEvent;
import com.jarvis.common.event.TokenEvent;
import com.jarvis.common.model.ActiveModelService;
import com.jarvis.common.model.ModelCapability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Executes the selected model through the selected provider.
 */
@Service
@Order(90)
public class ModelExecutionStage implements PipelineStage {

    private static final Logger TELEMETRY_LOGGER = LoggerFactory.getLogger(ModelExecutionStage.class);

    private final List<AIProvider> aiProviders;
    private final ToolTriggerStrategy toolTriggerStrategy;
    private final MainModelActionParser actionParser;
    private final CognitiveEventBus cognitiveEventBus;
    private final ActiveModelService activeModelService;

    /**
     * Creates the model execution stage.
     *
     * @param aiProviders available providers
     * @param activeModelService active model capability lookup, used to gate vision requests
     */
    public ModelExecutionStage(
            List<AIProvider> aiProviders,
            ToolTriggerStrategy toolTriggerStrategy,
            MainModelActionParser actionParser,
            CognitiveEventBus cognitiveEventBus,
            ActiveModelService activeModelService
    ) {
        this.aiProviders = List.copyOf(aiProviders);
        this.toolTriggerStrategy = toolTriggerStrategy;
        this.actionParser = actionParser;
        this.cognitiveEventBus = cognitiveEventBus;
        this.activeModelService = activeModelService;
    }

    @Override
    public String name() {
        return "ModelExecutionStage";
    }

    @Override
    public PipelineContext execute(PipelineContext context) {
        if (context.response() != null && !context.response().isBlank()) {
            return context;
        }
        if (!context.images().isEmpty() && !activeModelService.activeModelCapabilities().contains(ModelCapability.VISION)) {
            return respondNoVisionSupport(context);
        }
        String mainPrompt = toolTriggerStrategy.buildMainModelPrompt(context);
        recordPromptMetrics(context, mainPrompt);
        cognitiveEventBus.publish(CognitiveEventType.MAIN_MODEL_REQUEST, "REQUESTING", "Main model action request started", null, Map.of(
                "model", context.model(),
                "reasoningLevel", context.brain().reasoningLevel().name(),
                "hasImages", !context.images().isEmpty()
        ));
        long startedNano = System.nanoTime();
        StreamingStructuredResponseParser streamingParser = new StreamingStructuredResponseParser();
        StreamState streamState = new StreamState();
        selectProvider(context).stream(context.conversationId(), context.brain(), mainPrompt, AIJobType.MAIN_MODEL, context.images(), event -> {
            if (event instanceof TokenEvent tokenEvent) {
                StreamingStructuredResponseParser.ParserUpdate update = streamingParser.accept(tokenEvent.text());
                handleParserUpdate(context, update, streamState, true);
            }
            if (event instanceof ThinkingTokenEvent thinkingTokenEvent) {
                streamState.thinking.append(thinkingTokenEvent.text());
            }
            if (event instanceof GenerationFinishedEvent finishedEvent) {
                streamState.finishedEvent = finishedEvent;
            }
        });
        MainModelAction action = parseAction(context, streamingParser, streamState.thinking.toString());
        long durationMs = (System.nanoTime() - startedNano) / 1_000_000L;
        publishMainModelAction(context, action, durationMs);
        TELEMETRY_LOGGER.info(
                "[JARVIS_TOOL_DECISION] requestId={} initialModelDecision={} toolRequestedByModel={} autoTriggered=false reason={} modelCalls=1 durationMs={}",
                context.requestId(), action.type(), action.type() == MainModelActionType.TOOL_REQUEST, action.reason(), durationMs);
        return switch (action.type()) {
            case FINAL_ANSWER -> finishStreamedUserFacingResponse(context, streamedOrParsed(streamingParser, action.answer()), streamState)
                    .withMetadata("mainModelAction", action.type().name());
            case CLARIFICATION -> finishStreamedUserFacingResponse(context, streamedOrParsed(streamingParser, action.question()), streamState)
                    .withMetadata("mainModelAction", action.type().name());
            case TOOL_REQUEST -> context
                    .withMetadata("mainModelAction", action.type().name())
                    .withMetadata("toolGoal", action.goal())
                    .withMetadata("toolReason", action.reason())
                    .withMetadata("toolContext", action.context())
                    .withMetadata("mainModelDurationMs", durationMs);
        };
    }

    /**
     * Responds directly, without ever calling the model, when images were attached but the
     * currently active model does not report vision support - never attempt to analyze an image
     * with a text-only model.
     *
     * @param context pipeline context with unresolved images
     * @return context carrying the friendly fallback answer
     */
    private PipelineContext respondNoVisionSupport(PipelineContext context) {
        cognitiveEventBus.publish(CognitiveEventType.MAIN_MODEL_ACTION, "NO_VISION_SUPPORT",
                "Active model does not support vision, image request rejected without calling the model", null, Map.of(
                        "model", context.model(),
                        "images", context.images().size()
                ));
        String message = "Aktualnie wybrany model (" + context.model() + ") nie obsluguje analizy obrazow. "
                + "Wybierz model z obsluga Vision (np. Gemma 4, Qwen 3.5), aby przeanalizowac przeslany obraz.";
        return finishStreamedUserFacingResponse(context, message, new StreamState())
                .withMetadata("mainModelAction", MainModelActionType.FINAL_ANSWER.name());
    }

    private void handleParserUpdate(
            PipelineContext context,
            StreamingStructuredResponseParser.ParserUpdate update,
            StreamState streamState,
            boolean streamUserFacingText
    ) {
        update.detectedType().ifPresent(type -> handleDetectedType(context, type, streamState, streamUserFacingText));
        if (streamUserFacingText && update.streamedText() != null && !update.streamedText().isEmpty()) {
            streamAnswerChunk(context, update.streamedText(), streamState);
        }
    }

    private MainModelAction parseAction(
            PipelineContext context,
            StreamingStructuredResponseParser streamingParser,
            String privateThinking
    ) {
        String raw = streamingParser.raw();
        try {
            return actionParser.parse(raw);
        } catch (MainModelActionParsingException first) {
            MainModelAction streamedAction = actionFromStreamedValue(streamingParser);
            if (streamedAction != null) {
                return streamedAction;
            }
            MainModelAction repairedFromThinking = repairPrivateThinking(context, privateThinking, first.getMessage());
            if (repairedFromThinking != null) {
                return repairedFromThinking;
            }
            if (!looksLikeStructuredAction(raw)) {
                return safeFinalAnswerFromRaw(raw, first.getMessage());
            }
            String repaired = selectProvider(context).chat(context.brain(), repairPrompt(raw), AIJobType.DEBUG).response();
            if (isRepairArtifact(repaired)) {
                cognitiveEventBus.publish(CognitiveEventType.MAIN_MODEL_ACTION, "INVALID",
                        "Main model repair returned an internal repair artifact", null, Map.of(
                                "repairAttempted", true,
                                "error", "repair-artifact"
                        ));
                return safeFallbackAnswer("Nie moge teraz bezpiecznie przygotowac odpowiedzi, bo model nie zwrocil poprawnej tresci. Sprobuj ponownie.");
            }
            try {
                return actionParser.parse(repaired);
            } catch (MainModelActionParsingException second) {
                cognitiveEventBus.publish(CognitiveEventType.MAIN_MODEL_ACTION, "INVALID",
                        "Main model returned invalid action JSON", null, Map.of(
                                "repairAttempted", true,
                                "error", second.getMessage()
                        ));
                if (repaired != null && !repaired.isBlank() && !looksLikeStructuredAction(repaired)) {
                    return safeFinalAnswerFromRaw(repaired, second.getMessage());
                }
                return safeFallbackAnswer("Nie moge teraz bezpiecznie przygotowac odpowiedzi, bo model nie zwrocil poprawnej tresci. Sprobuj ponownie.");
            }
        }
    }

    private MainModelAction repairPrivateThinking(PipelineContext context, String privateThinking, String error) {
        if (privateThinking == null || privateThinking.isBlank()) {
            return null;
        }
        String repaired = selectProvider(context).chat(context.brain(), repairPrompt(privateThinking), AIJobType.DEBUG).response();
        if (repaired == null || repaired.isBlank() || isRepairArtifact(repaired)) {
            cognitiveEventBus.publish(CognitiveEventType.MAIN_MODEL_ACTION, "INVALID",
                    "Main model returned only private thinking and repair failed", null, Map.of(
                            "repairAttempted", true,
                            "error", error == null ? "" : error
                    ));
            return null;
        }
        try {
            return actionParser.parse(repaired);
        } catch (MainModelActionParsingException exception) {
            cognitiveEventBus.publish(CognitiveEventType.MAIN_MODEL_ACTION, "INVALID",
                    "Main model private thinking repair returned invalid action JSON", null, Map.of(
                            "repairAttempted", true,
                            "error", exception.getMessage()
                    ));
            return null;
        }
    }

    private MainModelAction actionFromStreamedValue(StreamingStructuredResponseParser streamingParser) {
        String streamed = streamingParser.streamedValue();
        if (streamed == null || streamed.isBlank()) {
            return null;
        }
        return streamingParser.detectedType()
                .map(type -> switch (type) {
                    case FINAL_ANSWER -> actionParser.finalAnswer(streamed);
                    case CLARIFICATION -> new MainModelAction(MainModelActionType.CLARIFICATION, "", "", "", streamed.strip(), Map.of());
                    case TOOL_REQUEST -> null;
                })
                .orElse(null);
    }

    private MainModelAction safeFinalAnswerFromRaw(String raw, String error) {
        String value = raw == null ? "" : raw.strip();
        if (value.isBlank() || isRepairArtifact(value)) {
            cognitiveEventBus.publish(CognitiveEventType.MAIN_MODEL_ACTION, "INVALID",
                    "Main model returned no user-facing action", null, Map.of(
                            "repairAttempted", false,
                            "error", error == null ? "" : error
                    ));
            return safeFallbackAnswer("Nie moge teraz bezpiecznie przygotowac odpowiedzi, bo model nie zwrocil tresci odpowiedzi. Sprobuj ponownie.");
        }
        cognitiveEventBus.publish(CognitiveEventType.MAIN_MODEL_ACTION, "FALLBACK_FINAL_ANSWER",
                "Main model returned plain text instead of structured action", null, Map.of(
                        "repairAttempted", false,
                        "characters", value.length()
                ));
        return actionParser.finalAnswer(value);
    }

    private MainModelAction safeFallbackAnswer(String answer) {
        return actionParser.finalAnswer(answer);
    }

    private boolean looksLikeStructuredAction(String raw) {
        String value = raw == null ? "" : raw.strip();
        return value.startsWith("{")
                || value.startsWith("```")
                || value.contains("\"type\"")
                || value.contains("'type'");
    }

    private boolean isRepairArtifact(String value) {
        String normalized = value == null ? "" : value.toLowerCase(java.util.Locale.ROOT);
        return (normalized.contains("raw text") && normalized.contains("valid json"))
                || normalized.contains("needs to be repaired into valid json")
                || normalized.contains("repair this main model action");
    }

    private String repairPrompt(String raw) {
        return """
                Repair this main model action into valid JSON only.
                Allowed schemas:
                {"type":"FINAL_ANSWER","answer":"..."}
                {"type":"TOOL_REQUEST","goal":"...","reason":"...","context":{"importantEntities":[]}}
                {"type":"CLARIFICATION","question":"..."}

                Do not invent tool results. If the raw text is a normal safe user-facing answer, wrap it as FINAL_ANSWER.
                Raw:
                %s
                """.formatted(raw == null ? "" : raw);
    }

    private void handleDetectedType(
            PipelineContext context,
            MainModelActionType type,
            StreamState streamState,
            boolean streamUserFacingText
    ) {
        cognitiveEventBus.publish(CognitiveEventType.STRUCTURED_RESPONSE_DETECTED, type.name(),
                "Structured response type detected", null, Map.of(
                        "type", type.name(),
                        "model", context.model(),
                        "reasoningLevel", context.brain().reasoningLevel().name()
                ));
        if (type == MainModelActionType.FINAL_ANSWER) {
            cognitiveEventBus.publish(CognitiveEventType.ANSWER_STREAM_STARTED, "STARTED",
                    "Structured answer stream started", null, Map.of("type", type.name(), "model", context.model()));
            if (streamUserFacingText) {
                startAnswerStream(context, streamState);
            }
        } else if (type == MainModelActionType.CLARIFICATION) {
            cognitiveEventBus.publish(CognitiveEventType.QUESTION_STREAM_STARTED, "STARTED",
                    "Structured clarification stream started", null, Map.of("type", type.name(), "model", context.model()));
            if (streamUserFacingText) {
                startAnswerStream(context, streamState);
            }
        } else if (type == MainModelActionType.TOOL_REQUEST) {
            cognitiveEventBus.publish(CognitiveEventType.TOOL_REQUEST_DETECTED, "DETECTED",
                    "Structured tool request detected", null, Map.of("type", type.name(), "model", context.model()));
        }
    }

    private void startAnswerStream(PipelineContext context, StreamState streamState) {
        if (streamState.answerStarted) {
            return;
        }
        streamState.answerStarted = true;
        streamState.answerStartedNano = System.nanoTime();
        cognitiveEventBus.publish(CognitiveEventType.ANSWER_STARTED, "ANSWERING", "Answer started", null, Map.of(
                "model", context.model(),
                "source", "structured-main-model"
        ));
        cognitiveEventBus.publish(CognitiveEventType.STREAMING_STARTED, "STREAMING", "Streaming started", null, Map.of(
                "model", context.model(),
                "source", "structured-main-model"
        ));
    }

    private void streamAnswerChunk(PipelineContext context, String chunk, StreamState streamState) {
        if (chunk == null || chunk.isEmpty()) {
            return;
        }
        startAnswerStream(context, streamState);
        streamState.answerChunks++;
        streamState.answerCharacters += chunk.length();
        streamState.answer.append(chunk);
        cognitiveEventBus.publish(CognitiveEventType.ANSWER_TOKEN, "TOKEN", chunk, null, Map.of(
                "text", chunk,
                "index", streamState.answerChunks,
                "source", "structured-main-model"
        ));
        cognitiveEventBus.publish(CognitiveEventType.TOKEN, "TOKEN", chunk, null, Map.of(
                "text", chunk,
                "index", streamState.answerChunks,
                "source", "structured-main-model"
        ));
    }

    private PipelineContext finishStreamedUserFacingResponse(PipelineContext context, String response, StreamState streamState) {
        if (!streamState.answerStarted && response != null && !response.isBlank()) {
            streamAnswerChunk(context, response, streamState);
        }
        long durationMs = streamState.answerStartedNano == 0L ? 0L : (System.nanoTime() - streamState.answerStartedNano) / 1_000_000L;
        cognitiveEventBus.publish(CognitiveEventType.ANSWER_FINISHED, "FINISHED", "Answer finished", null, Map.of(
                "durationMs", durationMs,
                "characters", response == null ? 0 : response.length(),
                "tokens", Math.max(1, (response == null ? 0 : response.length()) / 4),
                "source", "structured-main-model"
        ));
        cognitiveEventBus.publish(CognitiveEventType.STREAMING_FINISHED, "FINISHED", "Streaming finished", null, Map.of(
                "generationTimeMs", streamState.finishedEvent == null ? 0 : streamState.finishedEvent.generationTimeMs(),
                "promptTokens", streamState.finishedEvent == null || streamState.finishedEvent.promptTokens() == null ? 0 : streamState.finishedEvent.promptTokens(),
                "completionTokens", streamState.finishedEvent == null || streamState.finishedEvent.completionTokens() == null
                        ? Math.max(1, (response == null ? 0 : response.length()) / 4)
                        : streamState.finishedEvent.completionTokens(),
                "tokensStreamed", Math.max(1, streamState.answerChunks),
                "tokensPerSecond", streamState.finishedEvent == null || streamState.finishedEvent.tokensPerSecond() == null ? 0.0d : streamState.finishedEvent.tokensPerSecond(),
                "source", "structured-main-model"
        ));
        GenerationFinishedEvent finished = streamState.finishedEvent == null
                ? GenerationFinishedEvent.create(context.conversationId(), 0, context.brain().type(), context.model(),
                null, Math.max(1, (response == null ? 0 : response.length()) / 4), null)
                : streamState.finishedEvent;
        return context.withResponse(response == null ? "" : response, finished);
    }

    private String streamedOrParsed(StreamingStructuredResponseParser parser, String parsed) {
        return parser.streamedValue().isBlank() ? parsed : parser.streamedValue();
    }

    private void publishMainModelAction(PipelineContext context, MainModelAction action, long durationMs) {
        cognitiveEventBus.publish(CognitiveEventType.MAIN_MODEL_ACTION, action.type().name(),
                "Main model action selected", null, Map.of(
                        "action", action.type().name(),
                        "goal", action.goal(),
                        "reason", action.reason(),
                        "durationMs", durationMs,
                        "model", context.model(),
                        "reasoningLevel", context.brain().reasoningLevel().name()
                ));
    }

    private void recordPromptMetrics(PipelineContext context, String mainPrompt) {
        int totalPromptChars = safeLength(mainPrompt);
        int basePromptChars = safeLength(context.prompt());
        int toolCapabilityChars = Math.max(0, totalPromptChars - basePromptChars);
        int conversationChars = context.conversation().stream()
                .mapToInt(message -> safeLength(message.content()))
                .sum();
        int knowledgeChars = context.knowledgeContext().totalCharacters();
        int userChars = safeLength(context.request().message());
        int systemChars = Math.max(0, basePromptChars - conversationChars - knowledgeChars - userChars);
        int estimatedTokens = Math.max(1, totalPromptChars / 4);
        InferenceDiagnostics diagnostics = InferenceDiagnosticsContext.current();
        if (diagnostics != null) {
            diagnostics.setSystemPromptChars(systemChars);
            diagnostics.setConversationContextChars(conversationChars);
            diagnostics.setKnowledgeContextChars(knowledgeChars);
            diagnostics.setToolCapabilityChars(toolCapabilityChars);
            diagnostics.setCurrentUserMessageChars(userChars);
            diagnostics.setTotalPromptChars(totalPromptChars);
            diagnostics.setPromptCharacters(totalPromptChars);
            diagnostics.setEstimatedPromptTokens(estimatedTokens);
        }
        cognitiveEventBus.publish(CognitiveEventType.EXECUTION_TRACE, "FINISHED", "Prompt size diagnostics", null, Map.ofEntries(
                Map.entry("stage", "PROMPT_SIZE_DIAGNOSTICS"),
                Map.entry("phase", "FINISHED"),
                Map.entry("durationMs", 0),
                Map.entry("systemPromptChars", systemChars),
                Map.entry("conversationContextChars", conversationChars),
                Map.entry("knowledgeContextChars", knowledgeChars),
                Map.entry("toolCapabilityChars", toolCapabilityChars),
                Map.entry("currentUserMessageChars", userChars),
                Map.entry("totalPromptChars", totalPromptChars),
                Map.entry("estimatedPromptTokens", estimatedTokens),
                Map.entry("model", context.model()),
                Map.entry("severity", "GREEN")
        ));
        org.slf4j.LoggerFactory.getLogger(ModelExecutionStage.class).info(
                "[PROMPT_METRICS] requestId={} systemChars={} conversationChars={} knowledgeChars={} toolChars={} userChars={} totalChars={} estimatedPromptTokens={}",
                context.requestId(), systemChars, conversationChars, knowledgeChars, toolCapabilityChars, userChars, totalPromptChars, estimatedTokens);
    }

    private int safeLength(String value) {
        return value == null ? 0 : value.length();
    }

    private AIProvider selectProvider(PipelineContext context) {
        return aiProviders.stream()
                .filter(provider -> provider.provider().equalsIgnoreCase(context.brain().provider()))
                .findFirst()
                .orElseThrow(() -> new AIProviderException("AI provider is not available: " + context.brain().provider()));
    }

    private static final class StreamState {
        private final StringBuilder answer = new StringBuilder();
        private final StringBuilder thinking = new StringBuilder();
        private GenerationFinishedEvent finishedEvent;
        private boolean answerStarted;
        private int answerChunks;
        private int answerCharacters;
        private long answerStartedNano;
    }
}
