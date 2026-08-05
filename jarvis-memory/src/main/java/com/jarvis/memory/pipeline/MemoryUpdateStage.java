package com.jarvis.memory.pipeline;

import org.springframework.core.annotation.Order;

/**
 * Defers memory updates to the background memory job queue.
 */
@Order(95)
public class MemoryUpdateStage implements PipelineStage {

    /**
     * Creates the deferred memory update stage.
     */
    public MemoryUpdateStage() {
    }

    @Override
    public String name() {
        return "MemoryUpdateStage";
    }

    @Override
    public PipelineContext execute(PipelineContext context) {
        return context.withMetadata("memoryUpdateDeferred", true);
    }
}
