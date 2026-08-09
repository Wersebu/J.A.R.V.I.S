package com.jarvis.memory.pipeline;

import com.jarvis.tools.ToolResult;
import com.jarvis.tools.runtime.ToolCallingResult;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Extracts trusted answer source metadata from executed WebSearchTool results.
 */
public class WebAnswerSourceExtractor {

    /** Default maximum number of sources exposed to the UI. */
    public static final int DEFAULT_LIMIT = 5;

    /**
     * Extracts trusted web sources from a tool-calling result.
     *
     * @param result tool-calling result
     * @return safe source maps for event metadata
     */
    public List<Map<String, Object>> extract(ToolCallingResult result) {
        return extract(result, DEFAULT_LIMIT);
    }

    /**
     * Extracts trusted web sources from a tool-calling result.
     *
     * @param result tool-calling result
     * @param limit maximum number of sources
     * @return safe source maps for event metadata
     */
    public List<Map<String, Object>> extract(ToolCallingResult result, int limit) {
        if (result == null || result.results().isEmpty() || limit <= 0) {
            return List.of();
        }
        List<Map<String, Object>> sources = new ArrayList<>();
        Set<String> seenUrls = new LinkedHashSet<>();
        Set<String> seenDomains = new LinkedHashSet<>();
        for (ToolResult toolResult : result.results()) {
            if (!isWebSearchResult(toolResult)) {
                continue;
            }
            if (Boolean.FALSE.equals(toolResult.data().get("sourceQualityAccepted"))) {
                continue;
            }
            Object results = toolResult.data().containsKey("acceptedResults")
                    ? toolResult.data().get("acceptedResults")
                    : toolResult.data().get("results");
            if (!(results instanceof List<?> list)) {
                continue;
            }
            for (Object item : list) {
                SourceCandidate candidate = candidate(item);
                if (candidate == null || !isSafeHttpUrl(candidate.url())) {
                    continue;
                }
                String normalizedUrl = normalizeUrl(candidate.url());
                String normalizedDomain = candidate.domain().toLowerCase(Locale.ROOT);
                if (!seenUrls.add(normalizedUrl) || !seenDomains.add(normalizedDomain)) {
                    continue;
                }
                sources.add(Map.of(
                        "title", candidate.title(),
                        "domain", candidate.domain(),
                        "url", candidate.url()
                ));
                if (sources.size() >= limit) {
                    return List.copyOf(sources);
                }
            }
        }
        return List.copyOf(sources);
    }

    /**
     * Extracts a display domain from a URL.
     *
     * @param url source URL
     * @return display domain or an empty string
     */
    public String domain(String url) {
        try {
            URI uri = new URI(url);
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return "";
            }
            String normalized = host.toLowerCase(Locale.ROOT);
            return normalized.startsWith("www.") ? normalized.substring(4) : normalized;
        } catch (URISyntaxException exception) {
            return "";
        }
    }

    /**
     * Returns whether a URL is safe to expose as a clickable external source.
     *
     * @param url URL
     * @return true for http/https URLs with a host
     */
    public boolean isSafeHttpUrl(String url) {
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && uri.getHost() != null
                    && !uri.getHost().isBlank();
        } catch (URISyntaxException exception) {
            return false;
        }
    }

    private boolean isWebSearchResult(ToolResult toolResult) {
        return toolResult != null
                && toolResult.success()
                && "web".equalsIgnoreCase(toolResult.tool())
                && "SEARCH_WEB".equalsIgnoreCase(toolResult.operation());
    }

    private SourceCandidate candidate(Object item) {
        if (!(item instanceof Map<?, ?> map)) {
            return null;
        }
        String url = text(map.get("url"));
        String domain = domain(url);
        if (domain.isBlank()) {
            return null;
        }
        String title = text(map.get("title"));
        if (title.isBlank()) {
            title = text(map.get("source"));
        }
        if (title.isBlank()) {
            title = domain;
        }
        return new SourceCandidate(title, url, domain);
    }

    private String normalizeUrl(String url) {
        return Objects.toString(url, "").trim();
    }

    private String text(Object value) {
        return Objects.toString(value, "").trim();
    }

    private record SourceCandidate(String title, String url, String domain) {
    }
}
