package com.jarvis.memory.research;

import com.jarvis.memory.pipeline.PipelineContext;

/**
 * Executes agentic knowledge research without using the classic PromptBuilder path.
 */
public interface ResearchEngine {

    /**
     * Runs research mode and returns a context containing the final response.
     *
     * @param context pipeline context
     * @return updated context
     */
    PipelineContext research(PipelineContext context);
}
