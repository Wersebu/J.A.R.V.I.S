package com.jarvis.ollama;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * Non-streaming Ollama generate request.
 *
 * @param model model name
 * @param prompt prompt text
 * @param stream whether streaming is enabled
 * @param think native reasoning level for thinking-capable models
 * @param keepAlive Ollama keep_alive value
 * @param options Ollama generation options
 */
public record OllamaGenerateRequest(
        String model,
        String prompt,
        boolean stream,
        String think,
        @JsonProperty("keep_alive") String keepAlive,
        Map<String, Object> options
) {
}
