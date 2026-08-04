package com.jarvis.brain.decision;

import com.jarvis.common.dto.ChatRequest;

/**
 * Classifies the likely type of a user task.
 */
public interface TaskAnalyzer {

    /**
     * Analyzes the request task type.
     *
     * @param request chat request
     * @return task analysis
     */
    TaskAnalysis analyze(ChatRequest request);
}
