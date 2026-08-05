package com.jarvis.memory.retrieval;

import com.jarvis.common.memory.MemoryRecord;

/**
 * Score assigned to one memory candidate.
 *
 * @param memory memory
 * @param score normalized score
 * @param reason scoring explanation
 */
public record MemoryScore(MemoryRecord memory, double score, String reason) {
}
