package com.jarvis.memory.retrieval;

import com.jarvis.common.memory.MemoryRecord;

import java.util.List;

/**
 * Selects candidate memories before expensive scoring.
 */
public interface MemoryCandidateRetriever {

    /**
     * Returns candidate memories for the query.
     *
     * @param query normalized query
     * @param memories available memories
     * @return candidates
     */
    List<MemoryRecord> candidates(MemoryQuery query, List<MemoryRecord> memories);
}
