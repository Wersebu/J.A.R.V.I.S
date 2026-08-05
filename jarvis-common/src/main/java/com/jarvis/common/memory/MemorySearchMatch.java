package com.jarvis.common.memory;

/**
 * A scored memory candidate returned by deterministic memory retrieval.
 *
 * @param memory memory record
 * @param score normalized score between 0.0 and 1.0
 * @param similarity raw embedding similarity when available
 * @param reason short match reason
 */
public record MemorySearchMatch(MemoryRecord memory, double score, double similarity, String reason) {

    /**
     * Creates a match without separate raw similarity.
     *
     * @param memory memory record
     * @param score normalized score
     * @param reason match reason
     */
    public MemorySearchMatch(MemoryRecord memory, double score, String reason) {
        this(memory, score, score, reason);
    }
}
