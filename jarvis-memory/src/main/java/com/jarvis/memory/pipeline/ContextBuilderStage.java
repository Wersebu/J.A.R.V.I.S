package com.jarvis.memory.pipeline;

import com.jarvis.knowledge.context.ContextBuilder;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

/**
 * Builds AI-ready knowledge context.
 */
@Service
@Order(70)
public class ContextBuilderStage implements PipelineStage {

    private final ContextBuilder contextBuilder;

    /**
     * Creates the context builder stage.
     *
     * @param contextBuilder context builder
     */
    public ContextBuilderStage(ContextBuilder contextBuilder) {
        this.contextBuilder = contextBuilder;
    }

    @Override
    public String name() {
        return "Context Building";
    }

    @Override
    public PipelineContext execute(PipelineContext context) {
        return context.withKnowledgeContext(contextBuilder.build(context.retrievalResult()));
    }
}
