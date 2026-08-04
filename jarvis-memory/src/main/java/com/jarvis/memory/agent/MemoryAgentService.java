package com.jarvis.memory.agent;

import com.jarvis.common.event.CognitiveEvent;
import com.jarvis.memory.pipeline.PipelineContext;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Runs background memory extraction after a conversation is finished.
 */
public interface MemoryAgentService {

    /**
     * Starts background memory analysis.
     *
     * @param context completed pipeline context
     * @param eventSink cognitive event sink
     * @return background task future
     */
    CompletableFuture<Void> analyzeAsync(PipelineContext context, Consumer<CognitiveEvent> eventSink);
}
