package com.jarvis.common.ai;

import java.util.List;

/**
 * Provider-independent chat message used for native tool-calling turns.
 *
 * @param role message role
 * @param content message content
 * @param toolCalls assistant tool calls attached to the message
 * @param toolCallId tool call identifier for tool result messages
 */
public record ModelMessage(
        String role,
        String content,
        List<ModelToolCall> toolCalls,
        String toolCallId
) {

    /**
     * Creates an immutable model message.
     */
    public ModelMessage {
        role = role == null ? "" : role;
        content = content == null ? "" : content;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        toolCallId = toolCallId == null ? "" : toolCallId;
    }

    /**
     * Creates a system message.
     *
     * @param content message content
     * @return system message
     */
    public static ModelMessage system(String content) {
        return new ModelMessage("system", content, List.of(), "");
    }

    /**
     * Creates a user message.
     *
     * @param content message content
     * @return user message
     */
    public static ModelMessage user(String content) {
        return new ModelMessage("user", content, List.of(), "");
    }

    /**
     * Creates an assistant message.
     *
     * @param content message content
     * @param toolCalls native tool calls
     * @return assistant message
     */
    public static ModelMessage assistant(String content, List<ModelToolCall> toolCalls) {
        return new ModelMessage("assistant", content, toolCalls, "");
    }

    /**
     * Creates a tool result message.
     *
     * @param toolCallId tool call identifier
     * @param content structured tool result content
     * @return tool message
     */
    public static ModelMessage tool(String toolCallId, String content) {
        return new ModelMessage("tool", content, List.of(), toolCallId);
    }
}
