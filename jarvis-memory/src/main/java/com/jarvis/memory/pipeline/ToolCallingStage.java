package com.jarvis.memory.pipeline;

import com.jarvis.common.ai.AIJobType;
import com.jarvis.common.ai.AIProvider;
import com.jarvis.common.ai.AIProviderException;
import com.jarvis.common.event.CognitiveEvent;
import com.jarvis.common.event.CognitiveEventType;
import com.jarvis.common.event.GenerationFinishedEvent;
import com.jarvis.common.event.TokenEvent;
import com.jarvis.common.memory.ConversationMessage;
import com.jarvis.tools.ToolResult;
import com.jarvis.tools.runtime.ToolCallingRequest;
import com.jarvis.tools.runtime.ToolCallingResult;
import com.jarvis.tools.runtime.ToolCallingRuntime;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Executes native tool-calling after the main model requested an external capability.
 */
@Service
@Order(92)
public class ToolCallingStage implements PipelineStage {

    private final ToolCallingRuntime toolCallingRuntime;
    private final List<AIProvider> aiProviders;
    private final MainModelActionParser actionParser;

    /**
     * Creates the tool-calling stage.
     *
     * @param toolCallingRuntime native tool-calling runtime
     */
    public ToolCallingStage(
            ToolCallingRuntime toolCallingRuntime,
            List<AIProvider> aiProviders,
            MainModelActionParser actionParser
    ) {
        this.toolCallingRuntime = toolCallingRuntime;
        this.aiProviders = List.copyOf(aiProviders);
        this.actionParser = actionParser;
    }

    @Override
    public String name() {
        return "ToolCallingStage";
    }

    @Override
    public PipelineContext execute(PipelineContext context) {
        if (context.response() != null && !context.response().isBlank()) {
            return context;
        }
        if (!"TOOL_REQUEST".equals(String.valueOf(context.metadata().getOrDefault("mainModelAction", "")))) {
            return context;
        }
        ToolCallingResult result = toolCallingRuntime.execute(new ToolCallingRequest(
                context.requestId(),
                context.conversationId(),
                context.request().message(),
                String.valueOf(context.metadata().getOrDefault("toolGoal", "")),
                String.valueOf(context.metadata().getOrDefault("toolReason", "")),
                toolBasePrompt(context),
                context.brain(),
                context.effectiveKnowledgeMode()
        ));
        String answer;
        if (!result.handled()) {
            answer = streamGuidedAnswer(context,
                    "Nie wykonalem narzedzia, poniewaz tool runtime nie zwrocil bezpiecznej akcji do wykonania.",
                    "tool-unhandled");
        } else {
            answer = streamToolFinalAnswer(context, result);
        }
        GenerationFinishedEvent finished = GenerationFinishedEvent.create(context.conversationId(), 0, context.brain().type(),
                context.model(), null, Math.max(1, answer.length() / 4), null);
        return context.withResponse(answer, finished)
                .withMetadata("toolCallingHandled", true)
                .withMetadata("toolCallingSteps", result.steps().size());
    }

    private String streamToolFinalAnswer(PipelineContext context, ToolCallingResult result) {
        if (result.results().isEmpty() && result.finalAnswer() != null && !result.finalAnswer().isBlank()) {
            return publishBufferedFallback(context, result.finalAnswer(), "tool-fallback");
        }
        String prompt = toolFinalAnswerPrompt(context, result);
        String fallback = fallbackToolAnswer(result);
        try {
            return streamPrompt(context, prompt, fallback);
        } catch (AIProviderException exception) {
            return publishBufferedFallback(context, fallback, "tool-fallback");
        }
    }

    private String streamGuidedAnswer(PipelineContext context, String guidance, String source) {
        String prompt = toolBasePrompt(context)
                + "\n\nWrite a concise user-facing answer in the user's language."
                + "\nReturn plain text only."
                + "\n\nAnswer guidance:\n" + guidance;
        try {
            return streamPrompt(context, prompt, guidance);
        } catch (AIProviderException exception) {
            return publishBufferedFallback(context, guidance, source);
        }
    }

    private String streamPrompt(PipelineContext context, String prompt, String fallback) {
        ToolAnswerStreamState streamState = new ToolAnswerStreamState();
        selectProvider(context).stream(context.conversationId(), context.brain(), prompt, AIJobType.MAIN_MODEL, event -> {
            if (event instanceof TokenEvent tokenEvent) {
                handleToolAnswerToken(context, tokenEvent.text(), streamState);
            }
            if (event instanceof GenerationFinishedEvent finishedEvent) {
                streamState.finishedEvent = finishedEvent;
            }
        });
        String answer = finishToolAnswerStream(context, streamState, fallback);
        return answer.isBlank() ? fallback : answer;
    }

    private void handleToolAnswerToken(PipelineContext context, String token, ToolAnswerStreamState streamState) {
        if (token == null || token.isEmpty()) {
            return;
        }
        streamState.raw.append(token);
        if (!streamState.modeDecided) {
            String raw = streamState.raw.toString();
            String stripped = raw.stripLeading();
            if (stripped.isEmpty()) {
                return;
            }
            streamState.structured = stripped.startsWith("{");
            streamState.modeDecided = true;
            if (!streamState.structured) {
                streamToolAnswerChunk(context, raw, streamState);
                streamState.raw.setLength(0);
                return;
            }
        }
        if (!streamState.structured) {
            streamToolAnswerChunk(context, token, streamState);
            streamState.raw.setLength(0);
            return;
        }
        StreamingStructuredResponseParser.ParserUpdate update = streamState.parser.accept(token);
        update.detectedType().ifPresent(type -> publishStructuredToolAnswerDetected(context, type));
        if (update.streamedText() != null && !update.streamedText().isEmpty()) {
            streamToolAnswerChunk(context, update.streamedText(), streamState);
        }
    }

    private String finishToolAnswerStream(PipelineContext context, ToolAnswerStreamState streamState, String fallback) {
        String answer = streamState.answer.toString();
        if (streamState.structured && answer.isBlank()) {
            answer = parsedStructuredToolAnswer(streamState.raw.toString(), fallback);
            if (!answer.isBlank()) {
                streamToolAnswerChunk(context, answer, streamState);
            }
        }
        if (!streamState.structured && !streamState.raw.isEmpty()) {
            answer = streamState.raw.toString();
            streamToolAnswerChunk(context, answer, streamState);
        }
        if (answer.isBlank()) {
            answer = fallback;
            streamToolAnswerChunk(context, answer, streamState);
        }
        long durationMs = streamState.answerStartedNano == 0L ? 0L : (System.nanoTime() - streamState.answerStartedNano) / 1_000_000L;
        publish(context, CognitiveEventType.ANSWER_FINISHED, "FINISHED", "Tool answer finished", Map.of(
                "durationMs", durationMs,
                "characters", answer.length(),
                "tokens", Math.max(1, answer.length() / 4),
                "source", "tool-final-answer"
        ));
        publish(context, CognitiveEventType.STREAMING_FINISHED, "FINISHED", "Tool answer streaming finished", Map.of(
                "generationTimeMs", streamState.finishedEvent == null ? 0 : streamState.finishedEvent.generationTimeMs(),
                "promptTokens", streamState.finishedEvent == null || streamState.finishedEvent.promptTokens() == null ? 0 : streamState.finishedEvent.promptTokens(),
                "completionTokens", streamState.finishedEvent == null || streamState.finishedEvent.completionTokens() == null
                        ? Math.max(1, answer.length() / 4)
                        : streamState.finishedEvent.completionTokens(),
                "tokensStreamed", Math.max(1, streamState.answerChunks),
                "tokensPerSecond", streamState.finishedEvent == null || streamState.finishedEvent.tokensPerSecond() == null ? 0.0d : streamState.finishedEvent.tokensPerSecond(),
                "source", "tool-final-answer"
        ));
        return answer;
    }

    private String parsedStructuredToolAnswer(String raw, String fallback) {
        try {
            MainModelAction action = actionParser.parse(raw);
            return switch (action.type()) {
                case FINAL_ANSWER -> action.answer();
                case CLARIFICATION -> action.question();
                case TOOL_REQUEST -> fallback;
            };
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    private void publishStructuredToolAnswerDetected(PipelineContext context, MainModelActionType type) {
        publish(context, CognitiveEventType.STRUCTURED_RESPONSE_DETECTED, type.name(),
                "Structured tool final response detected", Map.of(
                        "type", type.name(),
                        "model", context.model(),
                        "source", "tool-final-answer"
                ));
    }

    private void streamToolAnswerChunk(PipelineContext context, String chunk, ToolAnswerStreamState streamState) {
        if (chunk == null || chunk.isEmpty()) {
            return;
        }
        if (!streamState.answerStarted) {
            streamState.answerStarted = true;
            streamState.answerStartedNano = System.nanoTime();
            publish(context, CognitiveEventType.ANSWER_STARTED, "ANSWERING", "Tool answer started", Map.of(
                    "model", context.model(),
                    "source", "tool-final-answer"
            ));
            publish(context, CognitiveEventType.STREAMING_STARTED, "STREAMING", "Tool answer streaming started", Map.of(
                    "model", context.model(),
                    "source", "tool-final-answer"
            ));
        }
        streamState.answerChunks++;
        streamState.answer.append(chunk);
        publish(context, CognitiveEventType.ANSWER_TOKEN, "TOKEN", chunk, Map.of(
                "text", chunk,
                "index", streamState.answerChunks,
                "source", "tool-final-answer"
        ));
        publish(context, CognitiveEventType.TOKEN, "TOKEN", chunk, Map.of(
                "text", chunk,
                "index", streamState.answerChunks,
                "source", "tool-final-answer"
        ));
    }

    private String publishBufferedFallback(PipelineContext context, String answer, String source) {
        publish(context, CognitiveEventType.ANSWER_STARTED, "ANSWERING", "Fallback answer started", Map.of(
                "model", context.model(),
                "source", source
        ));
        publish(context, CognitiveEventType.ANSWER_TOKEN, "TOKEN", answer, Map.of(
                "text", answer,
                "index", 1,
                "source", source
        ));
        publish(context, CognitiveEventType.ANSWER_FINISHED, "FINISHED", "Fallback answer finished", Map.of(
                "durationMs", 0,
                "characters", answer.length(),
                "tokens", Math.max(1, answer.length() / 4),
                "source", source
        ));
        publish(context, CognitiveEventType.STREAMING_FINISHED, "FINISHED", "Fallback streaming finished", Map.of(
                "generationTimeMs", 0,
                "promptTokens", 0,
                "completionTokens", Math.max(1, answer.length() / 4),
                "tokensStreamed", 1,
                "tokensPerSecond", 0.0d,
                "source", source
        ));
        return answer;
    }

    private String toolFinalAnswerPrompt(PipelineContext context, ToolCallingResult result) {
        return toolBasePrompt(context)
                + "\n\nYou just completed the tool workflow. Now write the final user-facing answer."
                + "\nDo not reveal hidden chain-of-thought. You may briefly mention what was done."
                + "\nIf approval is required, clearly tell the user that a draft is waiting for approval."
                + "\nKeep the answer concise, natural, and in the user's language."
                + "\nReturn plain text only."
                + "\n\nUser request:\n" + context.request().message()
                + "\n\nTool observations:\n" + toolObservations(result)
                + "\n\nExisting final answer guidance:\n" + safe(result.finalAnswer());
    }

    private String toolObservations(ToolCallingResult result) {
        StringBuilder builder = new StringBuilder();
        for (ToolResult toolResult : result.results()) {
            builder.append("- Tool: ").append(toolResult.tool())
                    .append(", operation: ").append(toolResult.operation())
                    .append(", success: ").append(toolResult.success())
                    .append(", approvalRequired: ").append(toolResult.requiresApproval())
                    .append(", message: ").append(toolResult.message())
                    .append(", data: ").append(toolResult.data())
                    .append("\n");
        }
        if (builder.isEmpty()) {
            builder.append("- No concrete tool result was produced.\n");
        }
        return builder.toString();
    }

    private String fallbackToolAnswer(ToolCallingResult result) {
        if (result.finalAnswer() != null && !result.finalAnswer().isBlank()) {
            return result.finalAnswer();
        }
        return result.results().stream()
                .findFirst()
                .map(toolResult -> toolResult.requiresApproval()
                        ? "Przygotowalem szkic zmiany. Czeka na zatwierdzenie."
                        : toolResult.message().isBlank() ? "Zakonczylem prace z narzedziami." : toolResult.message())
                .orElse("Zakonczylem prace z narzedziami.");
    }

    private String toolBasePrompt(PipelineContext context) {
        StringBuilder builder = new StringBuilder();
        if (context.prompt() != null && !context.prompt().isBlank()) {
            builder.append(context.prompt());
            appendMainModelToolRequest(context, builder);
            return builder.toString();
        }
        builder.append("""
                You are J.A.R.V.I.S.

                Long-term memory policy:
                The Knowledge Workspace is the only authoritative long-term memory.
                Do not rely on legacy SQLite semantic memory.
                When asked to remember information permanently, use KnowledgeTool.

                """);
        if (!context.conversation().isEmpty()) {
            builder.append("""
                    === CONVERSATION CONTEXT ===

                    The following messages are recent working conversation context.
                    This is not durable long-term memory.
                    Use it only for continuity inside the current conversation.

                    ----------------------------------------

                    """);
            for (ConversationMessage message : context.conversation()) {
                builder.append(message.role().name())
                        .append(":\n")
                        .append(message.content())
                        .append("\n\n");
            }
            builder.append("=== END CONVERSATION CONTEXT ===\n\n");
        }
        builder.append("=== CURRENT USER MESSAGE ===\n\n")
                .append(context.request().message())
                .append("\n\n=== END CURRENT USER MESSAGE ===\n");
        String goal = String.valueOf(context.metadata().getOrDefault("toolGoal", ""));
        String reason = String.valueOf(context.metadata().getOrDefault("toolReason", ""));
        if (!goal.isBlank()) {
            appendMainModelToolRequest(goal, reason, builder);
        }
        return builder.toString();
    }

    private void appendMainModelToolRequest(PipelineContext context, StringBuilder builder) {
        String goal = String.valueOf(context.metadata().getOrDefault("toolGoal", ""));
        String reason = String.valueOf(context.metadata().getOrDefault("toolReason", ""));
        if (!goal.isBlank()) {
            appendMainModelToolRequest(goal, reason, builder);
        }
    }

    private void appendMainModelToolRequest(String goal, String reason, StringBuilder builder) {
        builder.append("\n=== MAIN MODEL TOOL REQUEST ===\n\n")
                .append("Goal:\n")
                .append(goal)
                .append("\n\nReason summary:\n")
                .append(reason)
                .append("\n\nThe main model requested an external capability. Now choose the concrete tool calls safely.\n")
                .append("=== END MAIN MODEL TOOL REQUEST ===\n");
    }

    private void publish(PipelineContext context, CognitiveEventType event, String status, String message, Map<String, Object> metadata) {
        context.cognitiveEventSink().accept(new CognitiveEvent(
                context.requestId(),
                context.conversationId(),
                Instant.now(),
                event,
                status,
                message,
                context.brain() == null ? null : context.brain().type(),
                context.model(),
                null,
                metadata
        ));
    }

    private AIProvider selectProvider(PipelineContext context) {
        return aiProviders.stream()
                .filter(provider -> provider.provider().equalsIgnoreCase(context.brain().provider()))
                .findFirst()
                .orElseThrow(() -> new AIProviderException("AI provider is not available: " + context.brain().provider()));
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private static final class ToolAnswerStreamState {
        private final StringBuilder raw = new StringBuilder();
        private final StringBuilder answer = new StringBuilder();
        private final StreamingStructuredResponseParser parser = new StreamingStructuredResponseParser();
        private GenerationFinishedEvent finishedEvent;
        private boolean modeDecided;
        private boolean structured;
        private boolean answerStarted;
        private int answerChunks;
        private long answerStartedNano;
    }

}
