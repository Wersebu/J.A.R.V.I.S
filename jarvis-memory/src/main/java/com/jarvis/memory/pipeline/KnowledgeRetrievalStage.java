package com.jarvis.memory.pipeline;

import com.jarvis.knowledge.retrieval.KnowledgeRetriever;
import com.jarvis.common.knowledge.KnowledgeMode;
import com.jarvis.knowledge.retrieval.RetrievalResult;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.util.List;

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
        if (context.effectiveKnowledgeMode() == KnowledgeMode.RESEARCH) {
            return context;
        }
        if (context.knowledgeAnalysis() == null || !context.knowledgeAnalysis().required()) {
            String query = context.request().message() == null ? "" : context.request().message().trim();
            return context.withRetrievalResult(new RetrievalResult(query, 0, 0, List.of()));
        }
        return context.withRetrievalResult(knowledgeRetriever.retrieve(context.request().message()));
    }
}
