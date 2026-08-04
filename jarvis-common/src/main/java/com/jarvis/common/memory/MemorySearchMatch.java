package com.jarvis.common.memory;

/**
 * A scored memory candidate returned by deterministic memory retrieval.
 *
 * @param memory memory record
 * @param score normalized score between 0.0 and 1.0
 * @param reason short deterministic match reason
 */
public record MemorySearchMatch(MemoryRecord memory, double score, String reason) {
}
