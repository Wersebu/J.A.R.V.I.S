package com.jarvis.memory.job;

import com.jarvis.memory.pipeline.PipelineContext;

/**
 * Submits completed conversations for background memory processing.
 */
public interface MemoryJobService {

    /**
     * Submits a completed pipeline context as an immutable memory job.
     *
     * @param context completed context
     * @return true when queued
     */
    boolean submit(PipelineContext context);
}
