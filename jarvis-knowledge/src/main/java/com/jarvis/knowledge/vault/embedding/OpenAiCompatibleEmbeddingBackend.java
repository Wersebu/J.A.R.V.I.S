package com.jarvis.knowledge.vault.embedding;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;

/**
 * OpenAI-compatible {@code POST /v1/embeddings}, e.g. Hugging Face text-embeddings-inference (CPU
 * image) serving {@code intfloat/multilingual-e5-base}. When {@code remoteTokenizer} is set, real
 * token counts come from the TEI {@code POST /tokenize} endpoint.
 */
public final class OpenAiCompatibleEmbeddingBackend implements EmbeddingBackend {

    private final HttpJson http;
    private final String rootUrl;
    private final String model;
    private final boolean remoteTokenizer;
    private volatile boolean tokenizerUnavailable;

    /**
     * Creates the backend.
     *
     * @param baseUrl server URL, with or without {@code /v1}
     * @param model model name sent in the request
     * @param apiKey optional bearer token
     * @param remoteTokenizer use the /tokenize endpoint for token counts
     * @param timeout request timeout
     */
    public OpenAiCompatibleEmbeddingBackend(String baseUrl, String model, String apiKey, boolean remoteTokenizer, Duration timeout) {
        this.http = new HttpJson(timeout, apiKey);
        String root = HttpJson.trimSlash(baseUrl);
        this.rootUrl = root.endsWith("/v1") ? root.substring(0, root.length() - 3) : root;
        this.model = model;
        this.remoteTokenizer = remoteTokenizer;
    }

    @Override
    public String describe() {
        return "openai:" + model;
    }

    @Override
    public List<float[]> embed(List<String> inputs) {
        HttpJson.Response response = http.post(rootUrl + "/v1/embeddings", Map.of("model", model, "input", inputs));
        if (!response.ok()) {
            String text = response.shortBody();
            String lower = text.toLowerCase(Locale.ROOT);
            if (response.status() == 413 || (lower.contains("token") && (lower.contains("less than") || lower.contains("too long")
                    || lower.contains("maximum") || lower.contains("exceed")))) {
                throw new InputTooLongException("Embedding server rejected input as too long: " + text);
            }
            throw new EmbeddingException("Embedding server failed status=" + response.status() + " body=" + text);
        }
        JsonNode data = response.json() == null ? null : response.json().get("data");
        if (data == null || !data.isArray() || data.size() != inputs.size()) {
            throw new EmbeddingException("Embedding server returned an unexpected payload for model " + model);
        }
        float[][] ordered = new float[inputs.size()][];
        int position = 0;
        for (JsonNode item : data) {
            int index = item.has("index") ? item.get("index").asInt() : position;
            JsonNode vector = item.get("embedding");
            float[] values = new float[vector.size()];
            for (int component = 0; component < values.length; component++) {
                values[component] = (float) vector.get(component).asDouble();
            }
            ordered[index] = values;
            position++;
        }
        List<float[]> vectors = new ArrayList<>(Arrays.asList(ordered));
        if (vectors.stream().anyMatch(vector -> vector == null)) {
            throw new EmbeddingException("Embedding server response is missing vectors");
        }
        return vectors;
    }

    @Override
    public OptionalInt countTokens(String text) {
        if (!remoteTokenizer || tokenizerUnavailable) {
            return OptionalInt.empty();
        }
        try {
            HttpJson.Response response = http.post(rootUrl + "/tokenize", Map.of("inputs", text, "add_special_tokens", true));
            if (!response.ok() || response.json() == null || !response.json().isArray()) {
                tokenizerUnavailable = response.status() == 404;
                return OptionalInt.empty();
            }
            JsonNode first = response.json().get(0);
            return first != null && first.isArray() ? OptionalInt.of(first.size()) : OptionalInt.of(response.json().size());
        } catch (EmbeddingException exception) {
            return OptionalInt.empty();
        }
    }
}
