package com.jarvis.common.ai;

import java.util.List;

/**
 * Provider-independent chat message used for native tool-calling turns.
 *
 * @param role message role
 * @param content message content
 * @param toolCalls assistant tool calls attached to the message
 * @param toolCallId tool call identifier for tool result messages
 * @param images images attached to this message (currently only meaningful on a {@code user}
 *         message), carried by reference - the same {@link ImageAttachment} instances resolved
 *         once by {@code ImageAttachmentStage}, never copied. Empty for every other message role.
 */
public record ModelMessage(
        String role,
        String content,
        List<ModelToolCall> toolCalls,
        String toolCallId,
        List<ImageAttachment> images
) {

    /**
     * Creates an immutable model message.
     */
    public ModelMessage {
        role = role == null ? "" : role;
        content = content == null ? "" : content;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        toolCallId = toolCallId == null ? "" : toolCallId;
        images = images == null ? List.of() : List.copyOf(images);
    }

    /**
     * Creates a message without images - backward-compatible for every existing call site.
     *
     * @param role message role
     * @param content message content
     * @param toolCalls assistant tool calls
     * @param toolCallId tool result call id
     */
    public ModelMessage(String role, String content, List<ModelToolCall> toolCalls, String toolCallId) {
        this(role, content, toolCalls, toolCallId, List.of());
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
     * Creates a user message carrying images alongside the text - the images ride along on every
     * later replay of this message (the growing tool-loop message list is resent in full each
     * turn), so they only need to be attached once, here, at the start of the loop.
     *
     * @param content message content
     * @param images images to attach, empty for a text-only user turn
     * @return user message
     */
    public static ModelMessage user(String content, List<ImageAttachment> images) {
        return new ModelMessage("user", content, List.of(), "", images);
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
