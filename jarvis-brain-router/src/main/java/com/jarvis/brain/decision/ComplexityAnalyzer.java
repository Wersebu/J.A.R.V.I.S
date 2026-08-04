package com.jarvis.brain.decision;

import com.jarvis.common.dto.ChatRequest;

/**
 * Estimates request complexity.
 */
public interface ComplexityAnalyzer {

    /**
     * Calculates complexity.
     *
     * @param request chat request
     * @param taskAnalysis task analysis
     * @param knowledgeAnalysis knowledge analysis
     * @return complexity score
     */
    ComplexityScore analyze(ChatRequest request, TaskAnalysis taskAnalysis, KnowledgeAnalysis knowledgeAnalysis);
}
