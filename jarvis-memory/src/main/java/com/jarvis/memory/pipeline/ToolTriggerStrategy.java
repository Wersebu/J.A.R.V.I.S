package com.jarvis.memory.pipeline;

/**
 * Strategy responsible for preparing the main-model tool trigger contract.
 */
public interface ToolTriggerStrategy {

    /**
     * Wraps the normal prompt with the structured action contract.
     *
     * @param context pipeline context
     * @return prompt for the main model
     */
    String buildMainModelPrompt(PipelineContext context);
}
