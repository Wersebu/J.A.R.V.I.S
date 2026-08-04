package com.jarvis.brain.decision;

/**
 * Produces an execution plan from analyzer outputs.
 */
public interface DecisionEngine {

    /**
     * Creates an execution plan.
     *
     * @param taskAnalysis task analysis
     * @param complexityScore complexity score
     * @param knowledgeAnalysis knowledge analysis
     * @return execution plan
     */
    ExecutionPlan decide(TaskAnalysis taskAnalysis, ComplexityScore complexityScore, KnowledgeAnalysis knowledgeAnalysis);
}
