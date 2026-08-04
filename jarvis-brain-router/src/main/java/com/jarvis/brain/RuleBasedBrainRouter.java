package com.jarvis.brain;

import com.jarvis.common.ai.Brain;
import com.jarvis.common.dto.ChatRequest;
import com.jarvis.brain.decision.ComplexityAnalyzer;
import com.jarvis.brain.decision.ComplexityScore;
import com.jarvis.brain.decision.DecisionEngine;
import com.jarvis.brain.decision.ExecutionPlan;
import com.jarvis.brain.decision.KnowledgeAnalysis;
import com.jarvis.brain.decision.KnowledgeRequirementAnalyzer;
import com.jarvis.brain.decision.TaskAnalysis;
import com.jarvis.brain.decision.TaskAnalyzer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * Deterministic brain router backed by the modular decision engine.
 */
@Service
public class RuleBasedBrainRouter implements BrainRouter {

    private static final Logger LOGGER = LoggerFactory.getLogger(RuleBasedBrainRouter.class);

    private final BrainCatalog brainCatalog;
    private final TaskAnalyzer taskAnalyzer;
    private final KnowledgeRequirementAnalyzer knowledgeRequirementAnalyzer;
    private final ComplexityAnalyzer complexityAnalyzer;
    private final DecisionEngine decisionEngine;

    /**
     * Creates the decision-engine brain router.
     *
     * @param brainCatalog configured brain catalog
     * @param taskAnalyzer task analyzer
     * @param knowledgeRequirementAnalyzer knowledge analyzer
     * @param complexityAnalyzer complexity analyzer
     * @param decisionEngine decision engine
     */
    public RuleBasedBrainRouter(
            BrainCatalog brainCatalog,
            TaskAnalyzer taskAnalyzer,
            KnowledgeRequirementAnalyzer knowledgeRequirementAnalyzer,
            ComplexityAnalyzer complexityAnalyzer,
            DecisionEngine decisionEngine
    ) {
        this.brainCatalog = brainCatalog;
        this.taskAnalyzer = taskAnalyzer;
        this.knowledgeRequirementAnalyzer = knowledgeRequirementAnalyzer;
        this.complexityAnalyzer = complexityAnalyzer;
        this.decisionEngine = decisionEngine;
    }

    /**
     * Selects a brain using deterministic rules.
     *
     * @param request chat request
     * @return selected brain
     */
    @Override
    public Brain select(ChatRequest request) {
        return select(plan(request));
    }

    /**
     * Selects a brain from an execution plan.
     *
     * @param plan execution plan
     * @return selected brain
     */
    @Override
    public Brain select(ExecutionPlan plan) {
        Instant startedAt = Instant.now();
        long latencyMs = Duration.between(startedAt, Instant.now()).toMillis();
        Brain brain = brainCatalog.get(plan.selectedBrain())
                .withRoutingMetadata(plan.reason(), latencyMs);
        LOGGER.info(
                "[JARVIS] Selected Brain: {}, Selected Model: {}, TaskType: {}, Complexity: {}, Confidence: {}, Selection reason: {}, Router latency: {} ms",
                brain.type(),
                brain.model(),
                plan.taskType(),
                plan.complexityScore(),
                plan.confidence(),
                plan.reason(),
                latencyMs
        );
        return brain;
    }

    /**
     * Creates an explainable execution plan.
     *
     * @param request chat request
     * @return execution plan
     */
    @Override
    public ExecutionPlan plan(ChatRequest request) {
        TaskAnalysis taskAnalysis = taskAnalyzer.analyze(request);
        KnowledgeAnalysis knowledgeAnalysis = knowledgeRequirementAnalyzer.analyze(request, taskAnalysis);
        ComplexityScore complexityScore = complexityAnalyzer.analyze(request, taskAnalysis, knowledgeAnalysis);
        return decisionEngine.decide(taskAnalysis, complexityScore, knowledgeAnalysis);
    }
}
