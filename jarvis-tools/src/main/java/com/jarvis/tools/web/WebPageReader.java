package com.jarvis.tools.web;

/**
 * Reads normalized text from a public web page.
 */
public interface WebPageReader {

    /**
     * Fetches and normalizes readable text from a public URL.
     *
     * @param url page URL
     * @return normalized page content
     */
    WebPageContent read(String url);
}
