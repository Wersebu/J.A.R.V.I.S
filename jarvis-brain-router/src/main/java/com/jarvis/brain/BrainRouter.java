package com.jarvis.brain;

import com.jarvis.common.ai.Brain;
import com.jarvis.common.dto.ChatRequest;
import com.jarvis.brain.decision.ExecutionPlan;

/**
 * Selects a logical AI brain for a chat request.
 */
public interface BrainRouter {

    /**
     * Selects the best logical brain for the request.
     *
     * @param request chat request
     * @return selected brain
     */
    Brain select(ChatRequest request);

    /**
     * Resolves the configured brain for an existing execution plan.
     *
     * @param plan execution plan
     * @return selected brain
     */
    Brain select(ExecutionPlan plan);

    /**
     * Creates an explainable execution plan for a request.
     *
     * @param request chat request
     * @return execution plan
     */
    ExecutionPlan plan(ChatRequest request);
}
