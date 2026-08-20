package com.jarvis.common.ai;

import java.util.List;

/**
 * Provider-independent chat message used for native tool-calling turns.
 *
 * @param role message role
 * @param content message content
 * @param toolCalls assistant tool calls attached to the message
 * @param toolCallId tool call identifier for tool result messages
 * @param toolName the exact native function name the tool result belongs to (e.g. {@code
 *         mcp_roblox_list_roblox_studios__call}) - the same string the model itself used in the
 *         originating assistant turn's {@code tool_calls[].function.name}, never a different
 *         representation (not the underlying MCP tool's real name, not a re-derived label). Blank
 *         for every non-{@code tool} role message.
 * @param images images attached to this message (currently only meaningful on a {@code user}
 *         message), carried by reference - the same {@link ImageAttachment} instances resolved
 *         once by {@code ImageAttachmentStage}, never copied. Empty for every other message role.
 */
public record ModelMessage(
        String role,
        String content,
        List<ModelToolCall> toolCalls,
        String toolCallId,
        String toolName,
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
        toolName = toolName == null ? "" : toolName;
        images = images == null ? List.of() : List.copyOf(images);
    }

    /**
     * Creates a message without images or a tool name - backward-compatible for callers built
     * before either existed.
     *
     * @param role message role
     * @param content message content
     * @param toolCalls assistant tool calls
     * @param toolCallId tool result call id
     */
    public ModelMessage(String role, String content, List<ModelToolCall> toolCalls, String toolCallId) {
        this(role, content, toolCalls, toolCallId, "", List.of());
    }

    /**
     * Creates a message with images but no tool name - backward-compatible for callers built
     * before the tool name field existed (e.g. user turns, which never carry one anyway).
     *
     * @param role message role
     * @param content message content
     * @param toolCalls assistant tool calls
     * @param toolCallId tool result call id
     * @param images images attached to this message
     */
    public ModelMessage(String role, String content, List<ModelToolCall> toolCalls, String toolCallId, List<ImageAttachment> images) {
        this(role, content, toolCalls, toolCallId, "", images);
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
     * Creates a tool result message, with no associated tool name - back-compatible only; prefer
     * {@link #tool(String, String, String)} everywhere a real tool name is available (every real
     * production call site has one), so the provider can correctly associate the result with the
     * exact native function that produced it.
     *
     * @param toolCallId tool call identifier
     * @param content structured tool result content
     * @return tool message
     */
    public static ModelMessage tool(String toolCallId, String content) {
        return new ModelMessage("tool", content, List.of(), toolCallId, "", List.of());
    }

    /**
     * Creates a tool result message.
     *
     * @param toolCallId tool call identifier, matching the originating assistant turn's {@code
     *         tool_calls[].id} exactly
     * @param toolName the exact native function name this result belongs to, matching the
     *         originating assistant turn's {@code tool_calls[].function.name} exactly
     * @param content structured tool result content
     * @return tool message
     */
    public static ModelMessage tool(String toolCallId, String toolName, String content) {
        return new ModelMessage("tool", content, List.of(), toolCallId, toolName, List.of());
    }
}
