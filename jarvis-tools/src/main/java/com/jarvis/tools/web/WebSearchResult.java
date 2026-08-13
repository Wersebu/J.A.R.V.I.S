package com.jarvis.tools.web;

/**
 * Normalized lightweight web search result.
 *
 * @param title result title
 * @param url canonical result URL
 * @param snippet short result snippet
 * @param source source engine or host
 * @param publishedAt optional publish date/time as reported by the search engine, "" when unknown
 */
public record WebSearchResult(String title, String url, String snippet, String source, String publishedAt) {

    /**
     * Creates a normalized result.
     */
    public WebSearchResult {
        title = title == null ? "" : title.strip();
        url = url == null ? "" : url.strip();
        snippet = snippet == null ? "" : snippet.strip();
        source = source == null ? "" : source.strip();
        publishedAt = publishedAt == null ? "" : publishedAt.strip();
    }
}
