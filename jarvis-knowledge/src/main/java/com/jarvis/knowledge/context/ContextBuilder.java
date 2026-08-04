package com.jarvis.knowledge.context;

import com.jarvis.common.context.KnowledgeContext;
import com.jarvis.knowledge.retrieval.RetrievalResult;

/**
 * Builds AI-ready knowledge context from retrieval results.
 */
public interface ContextBuilder {

    /**
     * Builds structured knowledge context.
     *
     * @param retrievalResult retrieval result
     * @return knowledge context
     */
    KnowledgeContext build(RetrievalResult retrievalResult);
}
