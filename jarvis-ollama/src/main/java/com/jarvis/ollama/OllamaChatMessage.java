package com.jarvis.ollama;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Ollama chat message DTO.
 *
 * @param role message role
 * @param content message content
 * @param thinking native thinking content
 * @param toolCalls assistant tool calls
 * @param toolCallId tool result call id
 * @param images base64-encoded images attached to this message - the officially supported way to
 *               send images to a chat-templated multimodal model, unlike {@code /api/generate}'s
 *               top-level images field, which some models' templates never actually reference
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record OllamaChatMessage(
        String role,
        String content,
        String thinking,
        @JsonProperty("tool_calls") List<OllamaToolCall> toolCalls,
        @JsonProperty("tool_call_id") String toolCallId,
        List<String> images
) {

    /**
     * Creates a chat message without images.
     *
     * @param role message role
     * @param content message content
     * @param thinking native thinking content
     * @param toolCalls assistant tool calls
     * @param toolCallId tool result call id
     */
    public OllamaChatMessage(String role, String content, String thinking, List<OllamaToolCall> toolCalls, String toolCallId) {
        this(role, content, thinking, toolCalls, toolCallId, List.of());
    }
}
