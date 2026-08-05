package com.jarvis.memory.cognitive;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.jarvis.memory.embedding.StoredMemoryEmbedding;

/**
 * Stores stable semantic facts.
 */
public interface SemanticMemoryStore {

    /**
     * Saves a semantic fact.
     *
     * @param record fact record
     */
    void save(SemanticMemoryRecord record);

    /**
     * Updates an existing semantic fact.
     *
     * @param record fact record
     */
    void update(SemanticMemoryRecord record);

    /**
     * Finds a fact by subject, predicate, and value.
     *
     * @param subject fact subject
     * @param predicate fact predicate
     * @param value fact value
     * @return existing fact
     */
    Optional<SemanticMemoryRecord> findExact(String subject, String predicate, String value);

    /**
     * Finds a semantic memory by identifier.
     *
     * @param id memory identifier
     * @return memory record
     */
    Optional<SemanticMemoryRecord> findById(UUID id);

    /**
     * Stores embedding data for a semantic memory.
     *
     * @param memoryId memory identifier
     * @param model embedding model
     * @param dimension vector dimension
     * @param vector serialized vector
     */
    default void updateEmbedding(UUID memoryId, String model, int dimension, String vector) {
    }

    /**
     * Finds persisted embedding data for a semantic memory.
     *
     * @param memoryId memory identifier
     * @return stored embedding
     */
    default Optional<StoredMemoryEmbedding> findEmbedding(UUID memoryId) {
        return Optional.empty();
    }

    /**
     * Searches facts using a query.
     *
     * @param query query text
     * @param limit maximum number of results
     * @return matching facts
     */
    List<SemanticMemoryRecord> search(String query, int limit);

    /**
     * Lists all semantic memories.
     *
     * @return facts
     */
    List<SemanticMemoryRecord> listAll();

    /**
     * Deletes a semantic memory.
     *
     * @param id memory identifier
     * @return true if deleted
     */
    boolean delete(UUID id);
}
