package com.jarvis.tools.web;

/**
 * Structured SearXNG search request.
 *
 * @param query query text
 * @param maxResults maximum normalized results
 * @param language optional language
 * @param page optional page number
 * @param timeRange optional SearXNG time range
 * @param category optional SearXNG category
 * @param profile logical research profile
 */
public record WebSearchRequest(
        String query,
        int maxResults,
        String language,
        int page,
        String timeRange,
        String category,
        String profile
) {
}
