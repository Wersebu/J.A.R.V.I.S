package com.jarvis.memory.pipeline;

import com.jarvis.common.knowledge.KnowledgeMode;
import com.jarvis.knowledge.context.ContextBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

/**
 * Builds AI-ready knowledge context.
 */
@Service
@Order(70)
public class ContextBuilderStage implements PipelineStage {

    private static final Logger LOGGER = LoggerFactory.getLogger(ContextBuilderStage.class);

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
        return "ContextBuilderStage";
    }

    @Override
    public PipelineContext execute(PipelineContext context) {
        if (context.effectiveKnowledgeMode() == KnowledgeMode.RESEARCH) {
            return context;
        }
        LOGGER.info("""
                [JARVIS]
                CONTEXT BUILDER INPUT

                Retrieved memories:
                {}

                Retrieved knowledge documents:
                {}
                """,
                context.memoryContext().memoryCount(),
                context.retrievalResult() == null ? 0 : context.retrievalResult().documents().size());
        return context.withKnowledgeContext(contextBuilder.build(context.retrievalResult()));
    }
}
