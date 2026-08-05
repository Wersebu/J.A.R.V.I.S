package com.jarvis.common.embedding;

/**
 * Provider-independent contract for text embedding generation.
 */
public interface EmbeddingProvider {

    /**
     * Returns provider identifier.
     *
     * @return provider name
     */
    String provider();

    /**
     * Returns configured embedding model name.
     *
     * @return model name
     */
    String model();

    /**
     * Generates an embedding for text.
     *
     * @param text text to embed
     * @return embedding vector
     */
    EmbeddingVector embed(String text);
}
