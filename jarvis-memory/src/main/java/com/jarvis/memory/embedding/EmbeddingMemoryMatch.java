package com.jarvis.memory.embedding;

import com.jarvis.common.memory.MemoryRecord;

/**
 * Memory matched by semantic embedding search.
 *
 * @param memory memory record
 * @param score final normalized score
 * @param similarity raw cosine similarity
 * @param reason scoring reason
 */
public record EmbeddingMemoryMatch(
        MemoryRecord memory,
        double score,
        double similarity,
        String reason
) {
}
