package com.jarvis.memory.embedding;

import com.jarvis.memory.cognitive.SemanticMemoryRecord;

/**
 * Generates, stores and searches memory embeddings.
 */
public interface EmbeddingMemoryEngine {

    /**
     * Ensures that a semantic memory has an embedding for the current model.
     *
     * @param record semantic memory
     */
    void index(SemanticMemoryRecord record);

    /**
     * Searches semantic memories with embedding similarity.
     *
     * @param query user query
     * @return search result
     */
    EmbeddingMemorySearchResult search(String query);
}
