package com.jarvis.knowledge.vault.embedding;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Dedicated Ollama embedding model via {@code POST /api/embed} (batch input).
 *
 * <p>{@code truncate=false} makes Ollama reject an over-long input instead of silently cutting it,
 * and with {@code forceCpu} the request carries {@code options.num_gpu=0} so the embedding model
 * is loaded into system RAM and never competes with the chat model and voice for VRAM.
 */
public final class OllamaEmbeddingBackend implements EmbeddingBackend {

    private final HttpJson http;
    private final String baseUrl;
    private final String model;
    private final boolean forceCpu;

    /**
     * Creates the backend.
     *
     * @param baseUrl Ollama URL
     * @param model embedding model
     * @param forceCpu run on CPU only
     * @param timeout request timeout
     */
    public OllamaEmbeddingBackend(String baseUrl, String model, boolean forceCpu, Duration timeout) {
        this.http = new HttpJson(timeout, "");
        this.baseUrl = HttpJson.trimSlash(baseUrl.isBlank() ? "http://localhost:11434" : baseUrl);
        this.model = model;
        this.forceCpu = forceCpu;
    }

    @Override
    public String describe() {
        return "ollama:" + model + (forceCpu ? " (cpu)" : "");
    }

    @Override
    public List<float[]> embed(List<String> inputs) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("input", inputs);
        body.put("truncate", false);
        body.put("keep_alive", "30m");
        if (forceCpu) {
            body.put("options", Map.of("num_gpu", 0));
        }
        HttpJson.Response response = http.post(baseUrl + "/api/embed", body);
        if (!response.ok()) {
            String text = response.shortBody();
            String lower = text.toLowerCase(Locale.ROOT);
            if (response.status() == 400 && (lower.contains("context length") || lower.contains("too long") || lower.contains("exceeds"))) {
                throw new InputTooLongException("Ollama rejected input as too long: " + text);
            }
            throw new EmbeddingException("Ollama embed failed status=" + response.status() + " body=" + text);
        }
        JsonNode embeddings = response.json() == null ? null : response.json().get("embeddings");
        if (embeddings == null || !embeddings.isArray() || embeddings.size() != inputs.size()) {
            throw new EmbeddingException("Ollama embed returned an unexpected payload for model " + model);
        }
        List<float[]> vectors = new ArrayList<>(inputs.size());
        for (JsonNode vector : embeddings) {
            float[] values = new float[vector.size()];
            for (int index = 0; index < values.length; index++) {
                values[index] = (float) vector.get(index).asDouble();
            }
            vectors.add(values);
        }
        return vectors;
    }
}
