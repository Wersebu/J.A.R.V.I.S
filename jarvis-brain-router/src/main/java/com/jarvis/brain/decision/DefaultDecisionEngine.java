package com.jarvis.brain.decision;

import com.jarvis.brain.BrainCatalog;
import com.jarvis.common.ai.BrainType;
import com.jarvis.common.ai.ReasoningLevel;
import org.springframework.stereotype.Service;

/**
 * Default explainable execution planner.
 */
@Service
public class DefaultDecisionEngine implements DecisionEngine {

    private final DecisionProperties properties;
    private final BrainCatalog brainCatalog;

    /**
     * Creates the decision engine.
     *
     * @param properties decision thresholds
     * @param brainCatalog configured brain catalog
     */
    public DefaultDecisionEngine(DecisionProperties properties, BrainCatalog brainCatalog) {
        this.properties = properties;
        this.brainCatalog = brainCatalog;
    }

    /**
     * Creates an execution plan.
     *
     * @param taskAnalysis task analysis
     * @param complexityScore complexity score
     * @param knowledgeAnalysis knowledge analysis
     * @return execution plan
     */
    @Override
    public ExecutionPlan decide(
            TaskAnalysis taskAnalysis,
            ComplexityScore complexityScore,
            KnowledgeAnalysis knowledgeAnalysis
    ) {
        int estimatedPromptTokens = estimatePromptTokens(complexityScore, knowledgeAnalysis);
        ReasoningLevel reasoningLevel = selectReasoningLevel(taskAnalysis, complexityScore, knowledgeAnalysis, estimatedPromptTokens);
        BrainType brain = selectedBrain(reasoningLevel);
        String model = brainCatalog.get(brain).model();
        String reason = reason(taskAnalysis, complexityScore, knowledgeAnalysis, estimatedPromptTokens);
        return new ExecutionPlan(
                taskAnalysis.taskType(),
                taskAnalysis.confidence(),
                complexityScore.score(),
                knowledgeAnalysis.required(),
                knowledgeAnalysis.estimatedDocumentCount(),
                estimatedPromptTokens,
                brain,
                model,
                reasoningLevel,
                reason
        );
    }

    private ReasoningLevel selectReasoningLevel(
            TaskAnalysis taskAnalysis,
            ComplexityScore complexityScore,
            KnowledgeAnalysis knowledgeAnalysis,
            int estimatedPromptTokens
    ) {
        if (taskAnalysis.taskType() == TaskType.PROGRAMMING
                || taskAnalysis.taskType() == TaskType.CODE_REVIEW
                || taskAnalysis.taskType() == TaskType.ANALYSIS
                || taskAnalysis.taskType() == TaskType.CONTENT_GENERATION
                || complexityScore.score() >= properties.reasoningComplexityThreshold()
                || isLargeGeneration(taskAnalysis, estimatedPromptTokens)) {
            return ReasoningLevel.HIGH;
        }
        if (taskAnalysis.taskType() == TaskType.KNOWLEDGE_QA || knowledgeAnalysis.required()) {
            return ReasoningLevel.MEDIUM;
        }
        return ReasoningLevel.LOW;
    }

    private BrainType selectedBrain(ReasoningLevel reasoningLevel) {
        return reasoningLevel == ReasoningLevel.LOW ? BrainType.FAST : BrainType.REASONING;
    }

    private boolean isLargeGeneration(TaskAnalysis taskAnalysis, int estimatedPromptTokens) {
        return estimatedPromptTokens >= properties.largePromptTokenThreshold()
                && (taskAnalysis.taskType() == TaskType.CREATIVE_WRITING
                || taskAnalysis.taskType() == TaskType.SUMMARIZATION);
    }

    private int estimatePromptTokens(ComplexityScore complexityScore, KnowledgeAnalysis knowledgeAnalysis) {
        int base = 120 + complexityScore.score() * 90;
        int knowledgeTokens = knowledgeAnalysis.estimatedContextSize() / 4;
        return base + knowledgeTokens;
    }

    private String reason(
            TaskAnalysis taskAnalysis,
            ComplexityScore complexityScore,
            KnowledgeAnalysis knowledgeAnalysis,
            int estimatedPromptTokens
    ) {
        if (taskAnalysis.taskType() == TaskType.CONVERSATION && complexityScore.score() <= 3) {
            return "Short conversation";
        }
        if (taskAnalysis.taskType() == TaskType.KNOWLEDGE_QA || knowledgeAnalysis.required()) {
            return "Knowledge question";
        }
        if (taskAnalysis.taskType() == TaskType.PROGRAMMING || taskAnalysis.taskType() == TaskType.CODE_REVIEW) {
            return "Programming task";
        }
        if (taskAnalysis.taskType() == TaskType.CONTENT_GENERATION
                || estimatedPromptTokens >= properties.largePromptTokenThreshold()) {
            return "Large content generation request";
        }
        if (taskAnalysis.taskType() == TaskType.ANALYSIS) {
            return "Analysis detected";
        }
        return complexityScore.reason();
    }
}
