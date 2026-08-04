package com.jarvis.brain.decision;

import com.jarvis.common.dto.ChatRequest;

/**
 * Estimates whether local knowledge will be needed.
 */
public interface KnowledgeRequirementAnalyzer {

    /**
     * Analyzes knowledge requirements.
     *
     * @param request chat request
     * @param taskAnalysis task analysis
     * @return knowledge analysis
     */
    KnowledgeAnalysis analyze(ChatRequest request, TaskAnalysis taskAnalysis);
}
