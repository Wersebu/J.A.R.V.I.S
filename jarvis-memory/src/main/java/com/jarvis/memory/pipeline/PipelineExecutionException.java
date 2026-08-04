package com.jarvis.memory.pipeline;

/**
 * Exception thrown when a cognitive pipeline stage fails.
 */
public class PipelineExecutionException extends RuntimeException {

    private final PipelineContext context;

    /**
     * Creates the pipeline exception.
     *
     * @param message failure message
     * @param cause cause
     * @param context context at failure time
     */
    public PipelineExecutionException(String message, Throwable cause, PipelineContext context) {
        super(message, cause);
        this.context = context;
    }

    /**
     * Returns the context at failure time.
     *
     * @return context
     */
    public PipelineContext context() {
        return context;
    }
}
