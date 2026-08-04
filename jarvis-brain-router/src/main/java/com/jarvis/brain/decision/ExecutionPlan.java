package com.jarvis.brain.decision;

import com.jarvis.common.ai.BrainType;
import com.jarvis.common.ai.ReasoningLevel;

/**
 * Immutable execution plan produced before model execution.
 *
 * @param taskType detected task type
 * @param confidence task classification confidence
 * @param complexityScore request complexity score
 * @param knowledgeRequired whether knowledge is required
 * @param estimatedKnowledgeDocuments estimated documents
 * @param estimatedPromptTokens estimated prompt tokens
 * @param selectedBrain selected logical brain
 * @param selectedModel configured model name
 * @param reasoningLevel selected native reasoning level
 * @param reason decision explanation
 */
public record ExecutionPlan(
        TaskType taskType,
        double confidence,
        int complexityScore,
        boolean knowledgeRequired,
        int estimatedKnowledgeDocuments,
        int estimatedPromptTokens,
        BrainType selectedBrain,
        String selectedModel,
        ReasoningLevel reasoningLevel,
        String reason
) {
}
