package com.jarvis.memory.pipeline;

/**
 * One independent processing step in the cognitive pipeline.
 */
public interface PipelineStage {

    /**
     * Returns the stage name.
     *
     * @return stage name
     */
    String name();

    /**
     * Executes this stage.
     *
     * @param context input context
     * @return updated context
     */
    PipelineContext execute(PipelineContext context);
}
