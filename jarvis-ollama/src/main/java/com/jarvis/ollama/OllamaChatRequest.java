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
 * @param think native reasoning control: a reasoning-effort string ("low"/"medium"/"high") or a
 *              {@link Boolean} to plainly enable/disable thinking - see
 *              {@code OllamaGenerateRequest.think} for why this is {@code Object}
 * @param keepAlive Ollama keep_alive value
 * @param options generation options
 */
public record OllamaChatRequest(
        String model,
        List<OllamaChatMessage> messages,
        List<Map<String, Object>> tools,
        boolean stream,
        Object think,
        @JsonProperty("keep_alive") String keepAlive,
        Map<String, Object> options
) {
}
