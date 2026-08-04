package com.jarvis.memory.pipeline;

import com.jarvis.brain.decision.ComplexityAnalyzer;
import com.jarvis.common.event.CognitiveEventBus;
import com.jarvis.common.event.CognitiveEventType;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Calculates request complexity.
 */
@Service
@Order(30)
public class ComplexityStage implements PipelineStage {

    private final ComplexityAnalyzer complexityAnalyzer;
    private final CognitiveEventBus cognitiveEventBus;

    /**
     * Creates the complexity stage.
     *
     * @param complexityAnalyzer complexity analyzer
     * @param cognitiveEventBus event bus
     */
    public ComplexityStage(ComplexityAnalyzer complexityAnalyzer, CognitiveEventBus cognitiveEventBus) {
        this.complexityAnalyzer = complexityAnalyzer;
        this.cognitiveEventBus = cognitiveEventBus;
    }

    @Override
    public String name() {
        return "ComplexityStage";
    }

    @Override
    public PipelineContext execute(PipelineContext context) {
        var score = complexityAnalyzer.analyze(context.request(), context.taskAnalysis(), context.knowledgeAnalysis());
        cognitiveEventBus.publish(CognitiveEventType.COMPLEXITY_ANALYZED, "ANALYZED", "Complexity analyzed", null, Map.of(
                "complexity", score.score(),
                "reason", score.reason()
        ));
        return context.withComplexityScore(score);
    }
}
