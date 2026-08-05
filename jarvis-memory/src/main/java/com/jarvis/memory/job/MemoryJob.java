package com.jarvis.memory.job;

import com.jarvis.common.memory.MemoryRecord;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Immutable snapshot used by background memory processing.
 *
 * @param memoryJobId memory job identifier
 * @param sourceRequestId interactive request that produced the job
 * @param conversationId conversation identifier
 * @param userMessage latest user message
 * @param assistantAnswer final assistant answer
 * @param timestamp job creation timestamp
 * @param currentMemories relevant memories captured at submit time
 */
public record MemoryJob(
        UUID memoryJobId,
        String sourceRequestId,
        String conversationId,
        String userMessage,
        String assistantAnswer,
        Instant timestamp,
        List<MemoryRecord> currentMemories
) {

    /**
     * Creates an immutable memory job.
     */
    public MemoryJob {
        currentMemories = currentMemories == null ? List.of() : List.copyOf(currentMemories);
    }
}
