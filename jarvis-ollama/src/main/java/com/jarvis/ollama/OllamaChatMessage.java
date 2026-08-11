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
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record OllamaChatMessage(
        String role,
        String content,
        String thinking,
        @JsonProperty("tool_calls") List<OllamaToolCall> toolCalls,
        @JsonProperty("tool_call_id") String toolCallId
) {
}
