package com.jarvis.memory.cognitive;

import com.jarvis.common.memory.CognitiveMemoryContext;
import com.jarvis.common.memory.MemoryRecord;
import com.jarvis.common.memory.MemorySearchResult;

import java.util.List;
import java.util.UUID;

/**
 * Facade for cognitive memory operations.
 */
public interface CognitiveMemoryService {

    /**
     * Lists stored long-term memories.
     *
     * @return memory records
     */
    List<MemoryRecord> listAll();

    /**
     * Searches relevant memories.
     *
     * @param query query text
     * @return search result
     */
    MemorySearchResult search(String query);

    /**
     * Builds AI-ready memory context for a query.
     *
     * @param query query text
     * @return memory context
     */
    CognitiveMemoryContext retrieveContext(String query);

    /**
     * Updates memory from a completed conversation turn.
     *
     * @param conversationId conversation identifier
     * @param userMessage user message
     * @param assistantResponse assistant response
     * @return performed mutations
     */
    List<MemoryMutation> updateFromConversation(String conversationId, String userMessage, String assistantResponse);

    /**
     * Rebuilds memory indexes.
     *
     * @return number of indexed memories
     */
    int reindex();

    /**
     * Deletes memory by identifier.
     *
     * @param id memory identifier
     * @return true if deleted
     */
    boolean delete(UUID id);
}
