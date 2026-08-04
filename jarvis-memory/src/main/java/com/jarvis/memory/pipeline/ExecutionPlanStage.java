package com.jarvis.memory.pipeline;

import com.jarvis.brain.BrainRouter;
import com.jarvis.brain.decision.DecisionEngine;
import com.jarvis.common.event.CognitiveEventBus;
import com.jarvis.common.event.CognitiveEventType;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Creates an execution plan and resolves the selected brain.
 */
@Service
@Order(50)
public class ExecutionPlanStage implements PipelineStage {

    private final DecisionEngine decisionEngine;
    private final BrainRouter brainRouter;
    private final CognitiveEventBus cognitiveEventBus;

    /**
     * Creates the execution plan stage.
     *
     * @param decisionEngine decision engine
     * @param brainRouter brain router
     * @param cognitiveEventBus event bus
     */
    public ExecutionPlanStage(DecisionEngine decisionEngine, BrainRouter brainRouter, CognitiveEventBus cognitiveEventBus) {
        this.decisionEngine = decisionEngine;
        this.brainRouter = brainRouter;
        this.cognitiveEventBus = cognitiveEventBus;
    }

    @Override
    public String name() {
        return "Execution Plan";
    }

    @Override
    public PipelineContext execute(PipelineContext context) {
        cognitiveEventBus.publish(CognitiveEventType.BRAIN_ROUTING_STARTED, "ROUTING", "Selecting brain", null, Map.of());
        var plan = decisionEngine.decide(context.taskAnalysis(), context.complexityScore(), context.knowledgeAnalysis());
        var brain = brainRouter.select(plan);
        cognitiveEventBus.updateBrain(brain.type(), brain.model());
        cognitiveEventBus.publish(CognitiveEventType.EXECUTION_PLAN_CREATED, "CREATED", "Execution plan created", null, Map.of(
                "taskType", plan.taskType().name(),
                "confidence", plan.confidence(),
                "complexity", plan.complexityScore(),
                "knowledgeRequired", plan.knowledgeRequired(),
                "estimatedKnowledgeDocuments", plan.estimatedKnowledgeDocuments(),
                "estimatedPromptTokens", plan.estimatedPromptTokens(),
                "brain", plan.selectedBrain().name(),
                "model", plan.selectedModel(),
                "reason", plan.reason()
        ));
        cognitiveEventBus.publish(CognitiveEventType.BRAIN_SELECTED, "SELECTED", "Brain selected", "brain:" + brain.type(), Map.of(
                "brain", brain.type().name(),
                "model", brain.model(),
                "reason", brain.selectionReason(),
                "latencyMs", brain.routerLatencyMs(),
                "taskType", plan.taskType().name(),
                "complexity", plan.complexityScore(),
                "confidence", plan.confidence(),
                "estimatedPromptTokens", plan.estimatedPromptTokens(),
                "knowledgeRequired", plan.knowledgeRequired(),
                "estimatedKnowledgeDocuments", plan.estimatedKnowledgeDocuments()
        ));
        return context.withExecution(plan, brain);
    }
}
