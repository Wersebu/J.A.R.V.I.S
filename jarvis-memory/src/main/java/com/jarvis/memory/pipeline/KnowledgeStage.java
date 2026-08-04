package com.jarvis.memory.pipeline;

import com.jarvis.brain.decision.KnowledgeRequirementAnalyzer;
import com.jarvis.common.event.CognitiveEventBus;
import com.jarvis.common.event.CognitiveEventType;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Estimates knowledge requirements.
 */
@Service
@Order(40)
public class KnowledgeStage implements PipelineStage {

    private final KnowledgeRequirementAnalyzer analyzer;
    private final CognitiveEventBus cognitiveEventBus;

    /**
     * Creates the knowledge analysis stage.
     *
     * @param analyzer knowledge requirement analyzer
     * @param cognitiveEventBus event bus
     */
    public KnowledgeStage(KnowledgeRequirementAnalyzer analyzer, CognitiveEventBus cognitiveEventBus) {
        this.analyzer = analyzer;
        this.cognitiveEventBus = cognitiveEventBus;
    }

    @Override
    public String name() {
        return "Knowledge Analysis";
    }

    @Override
    public PipelineContext execute(PipelineContext context) {
        var analysis = analyzer.analyze(context.request(), context.taskAnalysis());
        cognitiveEventBus.publish(CognitiveEventType.KNOWLEDGE_ANALYZED, "ANALYZED", "Knowledge requirements analyzed", null, Map.of(
                "knowledgeRequired", analysis.required(),
                "estimatedKnowledgeDocuments", analysis.estimatedDocumentCount(),
                "estimatedContextSize", analysis.estimatedContextSize(),
                "reason", analysis.reason()
        ));
        return context.withKnowledgeAnalysis(analysis);
    }
}
