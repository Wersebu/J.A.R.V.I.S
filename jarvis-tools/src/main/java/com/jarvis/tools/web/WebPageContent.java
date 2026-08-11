package com.jarvis.tools.web;

import java.util.List;

/**
 * Normalized readable content fetched from a public web page.
 *
 * @param url fetched URL
 * @param title page title
 * @param text normalized visible text
 * @param characters returned character count
 * @param truncated whether text was truncated
 * @param durationMs fetch duration
 * @param statusCode HTTP status code
 * @param contentType response content type
 * @param links trusted links extracted from the fetched page
 */
public record WebPageContent(
        String url,
        String title,
        String text,
        int characters,
        boolean truncated,
        long durationMs,
        int statusCode,
        String contentType,
        List<WebPageLink> links
) {

    /**
     * Link extracted from a fetched web page.
     *
     * @param title link title
     * @param url absolute URL
     */
    public record WebPageLink(String title, String url) {
    }

    /**
     * Creates page content for callers that do not need HTTP diagnostics.
     *
     * @param url fetched URL
     * @param title page title
     * @param text normalized visible text
     * @param characters returned character count
     * @param truncated whether text was truncated
     * @param durationMs fetch duration
     */
    public WebPageContent(String url, String title, String text, int characters, boolean truncated, long durationMs) {
        this(url, title, text, characters, truncated, durationMs, 200, "", List.of());
    }

    /**
     * Creates page content with diagnostics but no extracted links.
     *
     * @param url fetched URL
     * @param title page title
     * @param text normalized visible text
     * @param characters returned character count
     * @param truncated whether text was truncated
     * @param durationMs fetch duration
     * @param statusCode HTTP status code
     * @param contentType response content type
     */
    public WebPageContent(
            String url,
            String title,
            String text,
            int characters,
            boolean truncated,
            long durationMs,
            int statusCode,
            String contentType
    ) {
        this(url, title, text, characters, truncated, durationMs, statusCode, contentType, List.of());
    }
}
