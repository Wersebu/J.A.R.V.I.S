package com.jarvis.tools.web;

import java.util.List;

/**
 * Normalized web search response.
 *
 * @param query executed query
 * @param results normalized results
 * @param durationMs search duration in milliseconds
 */
public record WebSearchResponse(String query, List<WebSearchResult> results, long durationMs) {

    /**
     * Creates an immutable response.
     */
    public WebSearchResponse {
        query = query == null ? "" : query;
        results = results == null ? List.of() : List.copyOf(results);
    }
}
