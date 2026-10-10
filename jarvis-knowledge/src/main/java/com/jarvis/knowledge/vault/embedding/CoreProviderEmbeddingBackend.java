package com.jarvis.knowledge.vault.embedding;

import com.jarvis.common.embedding.EmbeddingProvider;
import com.jarvis.common.embedding.EmbeddingVector;

import java.util.ArrayList;
import java.util.List;

/**
 * Reuses the Core-wide {@link EmbeddingProvider} bean (the same one conversation memory uses,
 * configured by {@code jarvis.memory.embedding.model}). It embeds one input per request and
 * cannot force CPU execution; prefer {@code ollama} or {@code openai} for the vault.
 */
public final class CoreProviderEmbeddingBackend implements EmbeddingBackend {

    private final EmbeddingProvider provider;

    /**
     * Creates the backend.
     *
     * @param provider existing Core provider
     */
    public CoreProviderEmbeddingBackend(EmbeddingProvider provider) {
        this.provider = provider;
    }

    @Override
    public String describe() {
        return "core:" + provider.provider() + ":" + provider.model();
    }

    @Override
    public List<float[]> embed(List<String> inputs) {
        List<float[]> vectors = new ArrayList<>(inputs.size());
        for (String input : inputs) {
            EmbeddingVector vector;
            try {
                vector = provider.embed(input);
            } catch (RuntimeException exception) {
                throw new EmbeddingException("Core embedding provider failed: " + exception.getMessage(), exception);
            }
            if (vector == null || vector.values().isEmpty()) {
                throw new EmbeddingException("Core embedding provider returned an empty vector");
            }
            float[] values = new float[vector.values().size()];
            for (int index = 0; index < values.length; index++) {
                values[index] = vector.values().get(index).floatValue();
            }
            vectors.add(values);
        }
        return vectors;
    }
}
