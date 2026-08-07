package com.jarvis.memory.pipeline;

import com.jarvis.common.ai.AIProvider;
import com.jarvis.common.ai.AIProviderException;
import com.jarvis.common.ai.AIJobType;
import com.jarvis.common.event.ChatEventType;
import com.jarvis.common.diagnostics.InferenceDiagnostics;
import com.jarvis.common.diagnostics.InferenceDiagnosticsContext;
import com.jarvis.common.event.CognitiveEventBus;
import com.jarvis.common.event.CognitiveEventType;
import com.jarvis.common.event.GenerationFinishedEvent;
import com.jarvis.common.event.StatusChangedEvent;
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
        StringBuilder envelopeBuilder = new StringBuilder();
        GenerationFinishedHolder finishedHolder = new GenerationFinishedHolder();
        selectProvider(context).stream(context.conversationId(), context.brain(), mainPrompt, AIJobType.MAIN_MODEL, event -> {
            if (event instanceof TokenEvent tokenEvent) {
                envelopeBuilder.append(tokenEvent.text());
            }
            if (event instanceof GenerationFinishedEvent finishedEvent) {
                finishedHolder.event = finishedEvent;
            }
        });
        MainModelAction action = parseAction(context, envelopeBuilder.toString());
        long durationMs = (System.nanoTime() - startedNano) / 1_000_000L;
        publishMainModelAction(context, action, durationMs);
        return switch (action.type()) {
            case FINAL_ANSWER -> publishUserFacingResponse(context, action.answer(), finishedHolder.event)
                    .withMetadata("mainModelAction", action.type().name());
            case CLARIFICATION -> publishUserFacingResponse(context, action.question(), finishedHolder.event)
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

    private PipelineContext publishUserFacingResponse(
            PipelineContext context,
            String answer,
            GenerationFinishedEvent mainFinishedEvent
    ) {
        publish(CognitiveEventType.ANSWER_STARTED, "ANSWERING", "Answer started", Map.of(
                "model", context.model(),
                "source", "main-model"
        ));
        publish(CognitiveEventType.ANSWER_TOKEN, "TOKEN", answer, Map.of(
                "text", answer,
                "index", 1,
                "source", "main-model"
        ));
        publish(CognitiveEventType.TOKEN, "TOKEN", answer, Map.of(
                "text", answer,
                "index", 1,
                "source", "main-model"
        ));
        publish(CognitiveEventType.ANSWER_FINISHED, "FINISHED", "Answer finished", Map.of(
                "durationMs", 0,
                "characters", answer.length(),
                "tokens", Math.max(1, answer.length() / 4),
                "source", "main-model"
        ));
        publish(CognitiveEventType.STREAMING_FINISHED, "FINISHED", "Streaming finished", Map.of(
                "generationTimeMs", mainFinishedEvent == null ? 0 : mainFinishedEvent.generationTimeMs(),
                "promptTokens", mainFinishedEvent == null || mainFinishedEvent.promptTokens() == null ? 0 : mainFinishedEvent.promptTokens(),
                "completionTokens", mainFinishedEvent == null || mainFinishedEvent.completionTokens() == null ? Math.max(1, answer.length() / 4) : mainFinishedEvent.completionTokens(),
                "tokensStreamed", 1,
                "tokensPerSecond", mainFinishedEvent == null || mainFinishedEvent.tokensPerSecond() == null ? 0.0d : mainFinishedEvent.tokensPerSecond(),
                "source", "main-model"
        ));
        GenerationFinishedEvent finished = mainFinishedEvent == null
                ? GenerationFinishedEvent.create(context.conversationId(), 0, context.brain().type(), context.model(),
                null, Math.max(1, answer.length() / 4), null)
                : mainFinishedEvent;
        context.modelEventSink().publish(StatusChangedEvent.create(ChatEventType.IDLE, context.conversationId(), "IDLE"));
        return context.withResponse(answer, finished);
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

    private void publish(CognitiveEventType event, String status, String message, Map<String, Object> metadata) {
        cognitiveEventBus.publish(event, status, message, null, metadata);
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

    private static final class GenerationFinishedHolder {
        private GenerationFinishedEvent event;
    }
}
