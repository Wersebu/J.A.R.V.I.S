package com.jarvis.knowledge.vault.embedding;

import com.jarvis.knowledge.vault.KnowledgeVaultProperties;
import com.jarvis.knowledge.vault.chunk.HeuristicTokenCounter;
import com.jarvis.knowledge.vault.chunk.TokenCounter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Embedding front door for the vault: applies the query prefix, normalizes vectors, pins and
 * enforces one vector dimension, tracks provider health, and exposes a fingerprint that identifies
 * the exact embedding space (provider, model, prefixes, normalization). Vectors from a different
 * fingerprint or dimension are never compared with each other.
 */
public final class VaultEmbeddingService {

    private final KnowledgeVaultProperties.Embedding settings;
    private final EmbeddingBackend backend;
    private final TokenCounter heuristic = new HeuristicTokenCounter();
    private final Map<String, Integer> remoteTokenCache = new ConcurrentHashMap<>();
    private volatile int pinnedDimension;
    private volatile boolean available;
    private volatile String lastError = "";
    private volatile Instant lastSuccess;
    private volatile Instant lastFailure;

    /**
     * Creates the service.
     *
     * @param settings embedding settings
     * @param backend backend, or {@code null} when embeddings are disabled
     */
    public VaultEmbeddingService(KnowledgeVaultProperties.Embedding settings, EmbeddingBackend backend) {
        this.settings = settings;
        this.backend = backend;
        this.pinnedDimension = settings.dimensions();
        this.available = backend != null;
    }

    /**
     * Returns whether a provider is configured.
     *
     * @return true when embeddings are enabled
     */
    public boolean enabled() {
        return backend != null;
    }

    /**
     * Returns the embedding space fingerprint.
     *
     * @return fingerprint, {@code ""} when disabled
     */
    public String fingerprint() {
        if (backend == null) {
            return "";
        }
        return settings.provider() + "|" + settings.model() + "|q=" + settings.queryPrefix() + "|p=" + settings.passagePrefix()
                + "|norm=" + settings.normalize();
    }

    /**
     * Returns the pinned vector dimension, 0 when not known yet.
     *
     * @return dimension
     */
    public int dimension() {
        return pinnedDimension;
    }

    /**
     * Pins a dimension read back from the persistent index.
     *
     * @param dimension stored dimension
     */
    public void pinDimension(int dimension) {
        if (pinnedDimension == 0 && dimension > 0) {
            pinnedDimension = dimension;
        }
    }

    /**
     * Returns the passage prefix applied to indexed fragments.
     *
     * @return prefix
     */
    public String passagePrefix() {
        return backend == null ? "" : settings.passagePrefix();
    }

    /**
     * Returns the model input limit.
     *
     * @return max input tokens
     */
    public int maxInputTokens() {
        return settings.maxInputTokens();
    }

    /**
     * Returns the token counter used for chunk sizing: the provider tokenizer when configured and
     * reachable, otherwise the conservative heuristic.
     *
     * @return token counter
     */
    public TokenCounter tokenCounter() {
        if (backend == null || !"remote".equals(settings.tokenizer())) {
            return heuristic;
        }
        return text -> {
            Integer cached = remoteTokenCache.get(text);
            if (cached != null) {
                return cached;
            }
            OptionalInt remote = backend.countTokens(text);
            int count = remote.isPresent() ? remote.getAsInt() : heuristic.count(text);
            if (remote.isPresent() && remoteTokenCache.size() < 50_000) {
                remoteTokenCache.put(text, count);
            }
            return count;
        };
    }

    /**
     * Embeds a search query (query prefix applied).
     *
     * @param query query text
     * @return normalized vector
     */
    public float[] embedQuery(String query) {
        return embed(List.of(settings.queryPrefix() + query)).getFirst();
    }

    /**
     * Embeds fragment texts that already carry the passage prefix.
     *
     * @param embeddingTexts texts
     * @return vectors in order
     */
    public List<float[]> embedPassages(List<String> embeddingTexts) {
        List<float[]> vectors = new ArrayList<>(embeddingTexts.size());
        for (int start = 0; start < embeddingTexts.size(); start += settings.batchSize()) {
            vectors.addAll(embed(embeddingTexts.subList(start, Math.min(embeddingTexts.size(), start + settings.batchSize()))));
        }
        return vectors;
    }

    /**
     * Returns provider health for the status API.
     *
     * @return status values
     */
    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("provider", settings.provider());
        status.put("model", settings.model());
        status.put("backend", backend == null ? "disabled" : backend.describe());
        status.put("enabled", backend != null);
        status.put("available", backend != null && available);
        status.put("dimension", pinnedDimension);
        status.put("tokenizer", settings.tokenizer());
        status.put("maxInputTokens", settings.maxInputTokens());
        status.put("lastError", lastError);
        status.put("lastSuccess", lastSuccess == null ? "" : lastSuccess.toString());
        status.put("lastFailure", lastFailure == null ? "" : lastFailure.toString());
        return status;
    }

    /**
     * Returns whether the last call succeeded.
     *
     * @return availability
     */
    public boolean available() {
        return backend != null && available;
    }

    /**
     * Returns the last error message.
     *
     * @return error or {@code ""}
     */
    public String lastError() {
        return lastError;
    }

    private List<float[]> embed(List<String> inputs) {
        if (backend == null) {
            throw new EmbeddingBackend.EmbeddingException("Embeddings are disabled (knowledge.vault.embedding.provider=none)");
        }
        try {
            List<float[]> vectors = backend.embed(inputs);
            List<float[]> checked = new ArrayList<>(vectors.size());
            for (float[] vector : vectors) {
                checked.add(check(vector));
            }
            available = true;
            lastSuccess = Instant.now();
            return checked;
        } catch (EmbeddingBackend.InputTooLongException exception) {
            // The provider is healthy; the input is the problem.
            available = true;
            throw exception;
        } catch (RuntimeException exception) {
            available = false;
            lastFailure = Instant.now();
            lastError = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            throw exception instanceof EmbeddingBackend.EmbeddingException embeddingException
                    ? embeddingException
                    : new EmbeddingBackend.EmbeddingException(lastError, exception);
        }
    }

    private float[] check(float[] vector) {
        if (vector == null || vector.length == 0) {
            throw new EmbeddingBackend.EmbeddingException("Embedding provider returned an empty vector");
        }
        synchronized (this) {
            if (pinnedDimension == 0) {
                pinnedDimension = vector.length;
            }
        }
        if (vector.length != pinnedDimension) {
            throw new EmbeddingBackend.EmbeddingException("Embedding dimension mismatch: expected " + pinnedDimension
                    + " but provider returned " + vector.length + ". Rebuild the index after changing models.");
        }
        if (!settings.normalize()) {
            return vector;
        }
        double norm = 0.0d;
        for (float value : vector) {
            norm += value * value;
        }
        if (norm == 0.0d) {
            return vector;
        }
        float scale = (float) (1.0d / Math.sqrt(norm));
        float[] normalized = new float[vector.length];
        for (int index = 0; index < vector.length; index++) {
            normalized[index] = vector[index] * scale;
        }
        return normalized;
    }
}
