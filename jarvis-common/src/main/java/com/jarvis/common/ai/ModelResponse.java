package com.jarvis.common.ai;

import java.util.List;

/**
 * Provider-independent model response for non-streaming native tool turns.
 *
 * @param content visible assistant content
 * @param thinking native reasoning channel, when exposed by the provider
 * @param toolCalls native structured tool calls
 * @param finishReason provider finish reason
 * @param usage token usage
 */
public record ModelResponse(
        String content,
        String thinking,
        List<ModelToolCall> toolCalls,
        String finishReason,
        ModelUsage usage
) {

    /**
     * Creates an immutable model response.
     */
    public ModelResponse {
        content = content == null ? "" : content;
        thinking = thinking == null ? "" : thinking;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        finishReason = finishReason == null ? "" : finishReason;
        usage = usage == null ? new ModelUsage(0, 0, 0) : usage;
    }

    /**
     * Returns true when the model returned one or more native tool calls.
     *
     * @return true when tool calls are present
     */
    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }
}
