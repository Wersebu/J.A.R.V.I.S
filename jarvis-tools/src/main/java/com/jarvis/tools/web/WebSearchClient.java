package com.jarvis.tools.web;

/**
 * Provider-independent web search client.
 */
public interface WebSearchClient {

    /**
     * Searches the web and returns normalized results.
     *
     * @param query user query
     * @param maxResults maximum results to return
     * @return normalized search response
     */
    WebSearchResponse search(String query, int maxResults);

    /**
     * Searches the web using a structured request.
     *
     * @param request search request
     * @return normalized search response
     */
    default WebSearchResponse search(WebSearchRequest request) {
        return search(request.query(), request.maxResults());
    }
}
