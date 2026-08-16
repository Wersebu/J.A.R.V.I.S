package com.jarvis.ollama;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Non-streaming Ollama generate request.
 *
 * @param model model name
 * @param prompt prompt text
 * @param stream whether streaming is enabled
 * @param think native reasoning control: a reasoning-effort string ("low"/"medium"/"high", used
 *              for gpt-oss) or a {@link Boolean} to plainly enable/disable thinking (used by the
 *              Qwen thinking-budget continuation call). {@code Object} rather than {@code String}
 *              purely so both shapes serialize correctly - existing callers passing a String are
 *              unaffected.
 * @param keepAlive Ollama keep_alive value
 * @param options Ollama generation options
 * @param images base64-encoded images, sent natively to a vision-capable model; omitted entirely
 *               from the request body when empty, matching Ollama's plain text-only request shape
 */
public record OllamaGenerateRequest(
        String model,
        String prompt,
        boolean stream,
        Object think,
        @JsonProperty("keep_alive") String keepAlive,
        Map<String, Object> options,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> images
) {

    /**
     * Creates a text-only generate request.
     *
     * @param model model name
     * @param prompt prompt text
     * @param stream whether streaming is enabled
     * @param think native reasoning level for thinking-capable models
     * @param keepAlive Ollama keep_alive value
     * @param options Ollama generation options
     */
    public OllamaGenerateRequest(String model, String prompt, boolean stream, String think, String keepAlive, Map<String, Object> options) {
        this(model, prompt, stream, (Object) think, keepAlive, options, List.of());
    }
}
