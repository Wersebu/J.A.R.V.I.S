package com.jarvis.knowledge.retrieval;

/**
 * Provider-independent retrieval contract for knowledge documents.
 */
public interface KnowledgeRetriever {

    /**
     * Retrieves documents relevant to a query.
     *
     * @param query user query
     * @return retrieval result
     */
    RetrievalResult retrieve(String query);
}
