package com.jarvis.memory.agent;

import com.jarvis.memory.job.MemoryJob;

/**
 * Runs background memory extraction after a conversation is finished.
 */
public interface MemoryAgentService {

    /**
     * Runs memory analysis for a queued background job.
     *
     * @param job immutable memory job
     */
    void analyze(MemoryJob job);
}
