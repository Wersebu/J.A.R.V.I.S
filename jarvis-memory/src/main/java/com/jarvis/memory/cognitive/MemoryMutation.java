package com.jarvis.memory.cognitive;

import com.jarvis.common.memory.MemoryRecord;

/**
 * Describes a memory mutation performed by the memory engine.
 *
 * @param type mutation type
 * @param memory affected memory
 * @param reason deterministic reason
 */
public record MemoryMutation(MemoryMutationType type, MemoryRecord memory, String reason) {
}
