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
 */
public record WebPageContent(
        String url,
        String title,
        String text,
        int characters,
        boolean truncated,
        long durationMs
) {
}
