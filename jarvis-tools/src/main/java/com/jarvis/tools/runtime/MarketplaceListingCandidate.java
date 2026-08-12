package com.jarvis.tools.runtime;

/**
 * Candidate URL for marketplace listing collection.
 *
 * @param title candidate title
 * @param url candidate URL
 * @param source source/domain
 * @param snippet candidate snippet
 * @param priority priority, higher first
 */
public record MarketplaceListingCandidate(
        String title,
        String url,
        String source,
        String snippet,
        int priority
) {
}
