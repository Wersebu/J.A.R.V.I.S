package com.jarvis.ollama;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Ollama native tool call.
 *
 * @param id tool call id
 * @param type tool call type
 * @param function function payload
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record OllamaToolCall(
        String id,
        String type,
        OllamaToolFunction function
) {
}
