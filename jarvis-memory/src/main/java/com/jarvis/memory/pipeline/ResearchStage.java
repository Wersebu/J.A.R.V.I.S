package com.jarvis.memory.pipeline;

import com.jarvis.common.knowledge.KnowledgeMode;
import com.jarvis.memory.research.ResearchEngine;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

/**
 * Runs agentic knowledge research when the request uses RESEARCH mode.
 */
@Service
@Order(85)
public class ResearchStage implements PipelineStage {

    private final ResearchEngine researchEngine;

    /**
     * Creates the research stage.
     *
     * @param researchEngine research engine
     */
    public ResearchStage(ResearchEngine researchEngine) {
        this.researchEngine = researchEngine;
    }

    @Override
    public String name() {
        return "ResearchStage";
    }

    @Override
    public PipelineContext execute(PipelineContext context) {
        if (context.effectiveKnowledgeMode() != KnowledgeMode.RESEARCH) {
            return context;
        }
        return researchEngine.research(context);
    }
}
