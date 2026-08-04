package com.jarvis.memory.pipeline;

import java.time.Instant;

/**
 * Execution metric for a single cognitive pipeline stage.
 *
 * @param stageName stage name
 * @param startedAt start time
 * @param finishedAt end time
 * @param durationMs duration in milliseconds
 * @param success whether the stage succeeded
 * @param failure failure message, when available
 */
public record StageMetric(
        String stageName,
        Instant startedAt,
        Instant finishedAt,
        long durationMs,
        boolean success,
        String failure
) {
}
