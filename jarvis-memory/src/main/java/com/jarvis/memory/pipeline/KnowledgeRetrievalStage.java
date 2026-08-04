package com.jarvis.memory.pipeline;

import com.jarvis.knowledge.retrieval.KnowledgeRetriever;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

/**
 * Retrieves knowledge metadata for the request.
 */
@Service
@Order(60)
public class KnowledgeRetrievalStage implements PipelineStage {

    private final KnowledgeRetriever knowledgeRetriever;

    /**
     * Creates the retrieval stage.
     *
     * @param knowledgeRetriever knowledge retriever
     */
    public KnowledgeRetrievalStage(KnowledgeRetriever knowledgeRetriever) {
        this.knowledgeRetriever = knowledgeRetriever;
    }

    @Override
    public String name() {
        return "KnowledgeRetrievalStage";
    }

    @Override
    public PipelineContext execute(PipelineContext context) {
        return context.withRetrievalResult(knowledgeRetriever.retrieve(context.request().message()));
    }
}
