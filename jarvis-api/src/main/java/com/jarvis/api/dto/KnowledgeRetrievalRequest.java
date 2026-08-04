package com.jarvis.api.dto;

/**
 * Request body for knowledge retrieval.
 *
 * @param query retrieval query
 */
public record KnowledgeRetrievalRequest(String query) {
}
