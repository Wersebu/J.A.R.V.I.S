package com.jarvis.ollama;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Ollama native chat request.
 *
 * @param model model name
 * @param messages chat messages
 * @param tools native tool definitions
 * @param stream whether streaming is enabled
 * @param think native reasoning level
 * @param keepAlive Ollama keep_alive value
 * @param options generation options
 */
public record OllamaChatRequest(
        String model,
        List<OllamaChatMessage> messages,
        List<Map<String, Object>> tools,
        boolean stream,
        String think,
        @JsonProperty("keep_alive") String keepAlive,
        Map<String, Object> options
) {
}
