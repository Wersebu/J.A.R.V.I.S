package com.jarvis.tools.web;

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
 */
public record WebPageContent(
        String url,
        String title,
        String text,
        int characters,
        boolean truncated,
        long durationMs,
        int statusCode,
        String contentType
) {

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
        this(url, title, text, characters, truncated, durationMs, 200, "");
    }
}
