package com.jarvis.memory.pipeline;

import com.jarvis.common.ai.AIProvider;
import com.jarvis.common.ai.AIProviderException;
import com.jarvis.common.ai.AIJobType;
import com.jarvis.common.diagnostics.InferenceDiagnostics;
import com.jarvis.common.diagnostics.InferenceDiagnosticsContext;
import com.jarvis.common.event.CognitiveEventBus;
import com.jarvis.common.event.CognitiveEventType;
import com.jarvis.common.event.GenerationFinishedEvent;
import com.jarvis.common.event.TokenEvent;
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

    private final List<AIProvider> aiProviders;
    private final ToolTriggerStrategy toolTriggerStrategy;
    private final MainModelActionParser actionParser;
    private final CognitiveEventBus cognitiveEventBus;

    /**
     * Creates the model execution stage.
     *
     * @param aiProviders available providers
     */
    public ModelExecutionStage(
            List<AIProvider> aiProviders,
            ToolTriggerStrategy toolTriggerStrategy,
            MainModelActionParser actionParser,
            CognitiveEventBus cognitiveEventBus
    ) {
        this.aiProviders = List.copyOf(aiProviders);
        this.toolTriggerStrategy = toolTriggerStrategy;
        this.actionParser = actionParser;
        this.cognitiveEventBus = cognitiveEventBus;
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
        String mainPrompt = toolTriggerStrategy.buildMainModelPrompt(context);
        recordPromptMetrics(context, mainPrompt);
        cognitiveEventBus.publish(CognitiveEventType.MAIN_MODEL_REQUEST, "REQUESTING", "Main model action request started", null, Map.of(
                "model", context.model(),
                "reasoningLevel", context.brain().reasoningLevel().name()
        ));
        long startedNano = System.nanoTime();
        StreamingStructuredResponseParser streamingParser = new StreamingStructuredResponseParser();
        StreamState streamState = new StreamState();
        selectProvider(context).stream(context.conversationId(), context.brain(), mainPrompt, AIJobType.MAIN_MODEL, event -> {
            if (event instanceof TokenEvent tokenEvent) {
                StreamingStructuredResponseParser.ParserUpdate update = streamingParser.accept(tokenEvent.text());
                update.detectedType().ifPresent(type -> handleDetectedType(context, type, streamState));
                if (update.streamedText() != null && !update.streamedText().isEmpty()) {
                    streamAnswerChunk(context, update.streamedText(), streamState);
                }
            }
            if (event instanceof GenerationFinishedEvent finishedEvent) {
                streamState.finishedEvent = finishedEvent;
            }
        });
        MainModelAction action = parseAction(context, streamingParser.raw());
        long durationMs = (System.nanoTime() - startedNano) / 1_000_000L;
        publishMainModelAction(context, action, durationMs);
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

    private MainModelAction parseAction(PipelineContext context, String raw) {
        try {
            return actionParser.parse(raw);
        } catch (MainModelActionParsingException first) {
            String repaired = selectProvider(context).chat(context.brain(), repairPrompt(raw), AIJobType.DEBUG).response();
            try {
                return actionParser.parse(repaired);
            } catch (MainModelActionParsingException second) {
                cognitiveEventBus.publish(CognitiveEventType.MAIN_MODEL_ACTION, "INVALID",
                        "Main model returned invalid action JSON", null, Map.of(
                                "repairAttempted", true,
                                "error", second.getMessage()
                        ));
                return actionParser.finalAnswer("Nie moge bezpiecznie przetworzyc odpowiedzi modelu, poniewaz nie zwrocil poprawnego formatu akcji.");
            }
        }
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

    private void handleDetectedType(PipelineContext context, MainModelActionType type, StreamState streamState) {
        cognitiveEventBus.publish(CognitiveEventType.STRUCTURED_RESPONSE_DETECTED, type.name(),
                "Structured response type detected", null, Map.of(
                        "type", type.name(),
                        "model", context.model(),
                        "reasoningLevel", context.brain().reasoningLevel().name()
                ));
        if (type == MainModelActionType.FINAL_ANSWER) {
            cognitiveEventBus.publish(CognitiveEventType.ANSWER_STREAM_STARTED, "STARTED",
                    "Structured answer stream started", null, Map.of("type", type.name(), "model", context.model()));
            startAnswerStream(context, streamState);
        } else if (type == MainModelActionType.CLARIFICATION) {
            cognitiveEventBus.publish(CognitiveEventType.QUESTION_STREAM_STARTED, "STARTED",
                    "Structured clarification stream started", null, Map.of("type", type.name(), "model", context.model()));
            startAnswerStream(context, streamState);
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
        private GenerationFinishedEvent finishedEvent;
        private boolean answerStarted;
        private int answerChunks;
        private int answerCharacters;
        private long answerStartedNano;
    }
}
