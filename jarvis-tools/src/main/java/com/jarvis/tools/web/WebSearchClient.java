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
}
