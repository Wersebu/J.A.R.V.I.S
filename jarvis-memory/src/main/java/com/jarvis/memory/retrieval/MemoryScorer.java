package com.jarvis.memory.retrieval;

import com.jarvis.common.memory.MemoryRecord;

/**
 * Scores a memory against a normalized query.
 */
public interface MemoryScorer {

    /**
     * Scores one candidate memory.
     *
     * @param query normalized query
     * @param memory candidate memory
     * @return score
     */
    MemoryScore score(MemoryQuery query, MemoryRecord memory);
}
