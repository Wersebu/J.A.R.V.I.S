package com.jarvis.memory.pipeline;

import com.jarvis.common.event.CognitiveEvent;
import com.jarvis.common.event.CognitiveEventType;
import com.jarvis.common.event.GenerationFinishedEvent;
import com.jarvis.common.memory.ConversationMessage;
import com.jarvis.tools.runtime.ToolCallingRequest;
import com.jarvis.tools.runtime.ToolCallingResult;
import com.jarvis.tools.runtime.ToolCallingRuntime;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

/**
 * Executes native tool-calling after the main model requested an external capability.
 */
@Service
@Order(92)
public class ToolCallingStage implements PipelineStage {

    private final ToolCallingRuntime toolCallingRuntime;

    /**
     * Creates the tool-calling stage.
     *
     * @param toolCallingRuntime native tool-calling runtime
     */
    public ToolCallingStage(ToolCallingRuntime toolCallingRuntime) {
        this.toolCallingRuntime = toolCallingRuntime;
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
            answer = "Nie wykonalem narzedzia, poniewaz tool runtime nie zwrocil bezpiecznej akcji do wykonania.";
        } else {
            answer = result.finalAnswer() == null || result.finalAnswer().isBlank()
                    ? "Zakonczylem prace z narzedziami."
                    : result.finalAnswer();
        }
        publishAnswer(context, answer);
        GenerationFinishedEvent finished = GenerationFinishedEvent.create(
                context.conversationId(),
                0,
                context.brain().type(),
                context.model(),
                null,
                null,
                null
        );
        context.modelEventSink().publish(finished);
        return context.withResponse(answer, finished)
                .withMetadata("toolCallingHandled", true)
                .withMetadata("toolCallingSteps", result.steps().size());
    }

    private void publishAnswer(PipelineContext context, String answer) {
        publish(context, CognitiveEventType.ANSWER_STARTED, "ANSWERING", "Tool answer started", Map.of(
                "model", context.model(),
                "source", "tool"
        ));
        publish(context, CognitiveEventType.ANSWER_TOKEN, "TOKEN", answer, Map.of(
                "text", answer,
                "index", 1,
                "source", "tool"
        ));
        publish(context, CognitiveEventType.ANSWER_FINISHED, "FINISHED", "Tool answer finished", Map.of(
                "durationMs", 0,
                "characters", answer.length(),
                "tokens", Math.max(1, answer.length() / 4),
                "source", "tool"
        ));
        publish(context, CognitiveEventType.STREAMING_FINISHED, "FINISHED", "Tool response finished", Map.of(
                "generationTimeMs", 0,
                "promptTokens", 0,
                "completionTokens", Math.max(1, answer.length() / 4),
                "tokensStreamed", 1,
                "tokensPerSecond", 0.0d,
                "source", "tool"
        ));
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
}
