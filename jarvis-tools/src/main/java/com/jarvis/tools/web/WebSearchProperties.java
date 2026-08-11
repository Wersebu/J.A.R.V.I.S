package com.jarvis.tools.web;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.time.Duration;

/**
 * Configuration for the SearXNG-backed web search capability.
 *
 * @param enabled whether web search is available to the tool runtime
 * @param baseUrl local SearXNG base URL
 * @param defaultMaxResults default result limit
 * @param currentFactMaxResults current fact result limit
 * @param marketMaxResults market research result limit
 * @param maxSearchAttempts maximum web search attempts for one tool loop
 * @param maxPageReads maximum page reads for one tool loop
 * @param marketMinObservations preferred minimum market observations
 * @param hardMaxResults absolute result cap
 * @param snippetMaxLength maximum snippet length passed back to the model
 * @param pageMaxLength maximum fetched web page text length passed back to the model
 * @param connectTimeout connection timeout
 * @param readTimeout request/read timeout
 */
@ConfigurationProperties(prefix = "jarvis.web-search")
public record WebSearchProperties(
        Boolean enabled,
        String baseUrl,
        int defaultMaxResults,
        int currentFactMaxResults,
        int marketMaxResults,
        int maxSearchAttempts,
        int maxPageReads,
        int marketMinObservations,
        int hardMaxResults,
        int snippetMaxLength,
        int pageMaxLength,
        Duration connectTimeout,
        Duration readTimeout
) {

    /**
     * Backward-compatible constructor for older tests/configuration wiring.
     */
    public WebSearchProperties(
            Boolean enabled,
            String baseUrl,
            int defaultMaxResults,
            int hardMaxResults,
            int snippetMaxLength,
            int pageMaxLength,
            Duration connectTimeout,
            Duration readTimeout
    ) {
        this(enabled, baseUrl, defaultMaxResults, 8, 15, 4, 5, 3, hardMaxResults, snippetMaxLength, pageMaxLength,
                connectTimeout, readTimeout);
    }

    /**
     * Applies safe defaults.
     */
    @ConstructorBinding
    public WebSearchProperties {
        enabled = enabled == null ? Boolean.TRUE : enabled;
        baseUrl = baseUrl == null || baseUrl.isBlank() ? "http://127.0.0.1:8888" : stripTrailingSlash(baseUrl);
        defaultMaxResults = defaultMaxResults > 0 ? defaultMaxResults : 5;
        currentFactMaxResults = currentFactMaxResults > 0 ? currentFactMaxResults : 8;
        marketMaxResults = marketMaxResults > 0 ? marketMaxResults : 15;
        maxSearchAttempts = maxSearchAttempts > 0 ? maxSearchAttempts : 4;
        maxPageReads = maxPageReads > 0 ? maxPageReads : 5;
        marketMinObservations = marketMinObservations > 0 ? marketMinObservations : 3;
        hardMaxResults = hardMaxResults > 0 ? hardMaxResults : 15;
        snippetMaxLength = snippetMaxLength > 0 ? snippetMaxLength : 320;
        pageMaxLength = pageMaxLength > 0 ? pageMaxLength : 8000;
        connectTimeout = connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()
                ? Duration.ofSeconds(2)
                : connectTimeout;
        readTimeout = readTimeout == null || readTimeout.isNegative() || readTimeout.isZero()
                ? Duration.ofSeconds(8)
                : readTimeout;
    }

    /**
     * Returns whether the capability is enabled.
     *
     * @return true when enabled
     */
    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }

    /**
     * Caps a model-provided result limit.
     *
     * @param requested requested max results
     * @return safe bounded limit
     */
    public int cappedMaxResults(int requested) {
        int effective = requested > 0 ? requested : defaultMaxResults;
        return Math.max(1, Math.min(effective, hardMaxResults));
    }

    private static String stripTrailingSlash(String value) {
        String current = value.strip();
        while (current.endsWith("/")) {
            current = current.substring(0, current.length() - 1);
        }
        return current;
    }
}
