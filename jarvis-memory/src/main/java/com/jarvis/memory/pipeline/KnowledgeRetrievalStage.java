package com.jarvis.memory.pipeline;

import com.jarvis.knowledge.retrieval.RetrievalResult;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Placeholder stage that keeps {@link PipelineContext#retrievalResult()} non-null.
 *
 * <p>Knowledge access is exclusively model-driven: the main model must issue a
 * {@code knowledge__SEARCH_CONTENT} / {@code knowledge__READ_DOCUMENT} native tool call.
 * Core never searches the Knowledge Workspace on the model's behalf before the model
 * has had a chance to decide whether it needs it.
 */
@Service
@Order(60)
public class KnowledgeRetrievalStage implements PipelineStage {

    @Override
    public String name() {
        return "KnowledgeRetrievalStage";
    }

    @Override
    public PipelineContext execute(PipelineContext context) {
        String query = context.request().message() == null ? "" : context.request().message().trim();
        return context.withRetrievalResult(new RetrievalResult(query, 0, 0, List.of()));
    }
}
