package com.jarvis.memory.embedding;

import java.util.List;
import java.util.UUID;

/**
 * Embedding persisted for a semantic memory record.
 *
 * @param memoryId memory identifier
 * @param model embedding model
 * @param dimension vector dimension
 * @param vector vector values
 */
public record StoredMemoryEmbedding(
        UUID memoryId,
        String model,
        int dimension,
        List<Double> vector
) {

    /**
     * Creates an immutable stored embedding.
     */
    public StoredMemoryEmbedding {
        vector = vector == null ? List.of() : List.copyOf(vector);
    }
}
