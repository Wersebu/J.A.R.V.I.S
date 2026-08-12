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
        boolean marketplaceMode = addMarketplaceListingSources(result.results(), sources, seenUrls, limit);
        if (marketplaceMode) {
            return List.copyOf(sources);
        }
        for (ToolResult toolResult : result.results()) {
            if (!isWebSearchResult(toolResult)) {
                continue;
            }
            if (Boolean.FALSE.equals(toolResult.data().get("sourceQualityAccepted"))) {
                continue;
            }
            if ("READ_WEB_PAGE".equalsIgnoreCase(toolResult.operation())) {
                SourceCandidate readPage = readPageCandidate(toolResult);
                if (readPage != null) {
                    addSource(readPage, sources, seenUrls);
                    if (sources.size() >= limit) {
                        return List.copyOf(sources);
                    }
                }
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
                if (candidate == null || !addSource(candidate, sources, seenUrls)) {
                    continue;
                }
                if (sources.size() >= limit) {
                    return List.copyOf(sources);
                }
            }
        }
        return List.copyOf(sources);
    }

    private boolean addMarketplaceListingSources(
            List<ToolResult> results,
            List<Map<String, Object>> sources,
            Set<String> seenUrls,
            int limit
    ) {
        boolean foundMarketplacePayload = false;
        for (ToolResult toolResult : results) {
            if (toolResult == null || !"web".equalsIgnoreCase(toolResult.tool())) {
                continue;
            }
            Object rawListings = toolResult.data().get("marketplaceListings");
            if (!(rawListings instanceof List<?> listings) || listings.isEmpty()) {
                continue;
            }
            foundMarketplacePayload = true;
            for (Object item : listings) {
                SourceCandidate candidate = marketplaceCandidate(item);
                if (candidate == null || !addSource(candidate, sources, seenUrls)) {
                    continue;
                }
                if (sources.size() >= limit) {
                    return true;
                }
            }
        }
        return foundMarketplacePayload;
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
                && ("SEARCH_WEB".equalsIgnoreCase(toolResult.operation())
                || "READ_WEB_PAGE".equalsIgnoreCase(toolResult.operation()));
    }

    private boolean addSource(
            SourceCandidate candidate,
            List<Map<String, Object>> sources,
            Set<String> seenUrls
    ) {
        if (!isSafeHttpUrl(candidate.url())) {
            return false;
        }
        String normalizedUrl = normalizeUrl(candidate.url());
        if (!seenUrls.add(normalizedUrl)) {
            return false;
        }
        sources.add(Map.of(
                "title", candidate.title(),
                "domain", candidate.domain(),
                "url", candidate.url()
        ));
        return true;
    }

    private SourceCandidate readPageCandidate(ToolResult toolResult) {
        String url = text(toolResult.data().get("url"));
        String domain = domain(url);
        if (domain.isBlank()) {
            return null;
        }
        String title = text(toolResult.data().get("title"));
        if (title.isBlank()) {
            title = domain;
        }
        return new SourceCandidate(title, url, domain);
    }

    private List<SourceCandidate> readPageLinkCandidates(ToolResult toolResult) {
        Object links = toolResult.data().get("links");
        if (!(links instanceof List<?> list)) {
            return List.of();
        }
        List<SourceCandidate> concrete = new ArrayList<>();
        List<SourceCandidate> candidates = new ArrayList<>();
        for (Object item : list) {
            SourceCandidate candidate = candidate(item);
            if (candidate != null) {
                if (concreteListing(candidate.url())) {
                    concrete.add(candidate);
                } else {
                    candidates.add(candidate);
                }
            }
        }
        if (!concrete.isEmpty()) {
            concrete.addAll(candidates);
            return List.copyOf(concrete);
        }
        return List.copyOf(candidates);
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

    private SourceCandidate marketplaceCandidate(Object item) {
        if (!(item instanceof Map<?, ?> map) || !Boolean.TRUE.equals(map.get("verified"))) {
            return null;
        }
        String url = text(map.get("url"));
        String domain = domain(url);
        if (domain.isBlank()) {
            return null;
        }
        String title = text(map.get("title"));
        if (title.isBlank()) {
            title = domain;
        }
        return new SourceCandidate(title, url, domain);
    }

    private String normalizeUrl(String url) {
        return Objects.toString(url, "").trim();
    }

    private boolean concreteListing(String url) {
        try {
            URI uri = new URI(url);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
            if (host.endsWith("olx.pl")) {
                return path.contains("/d/oferta/") || path.contains("/oferta/");
            }
            if (host.endsWith("allegro.pl")) {
                return path.contains("/oferta/");
            }
            if (host.endsWith("ceneo.pl")) {
                return path.matches("/\\d+.*");
            }
            if (host.endsWith("x-kom.pl")) {
                return path.startsWith("/p/");
            }
            if (host.endsWith("morele.net")) {
                return path.matches(".*/\\d+/?$") && !path.contains("wyszukiwarka");
            }
            return false;
        } catch (URISyntaxException exception) {
            return false;
        }
    }

    private String text(Object value) {
        return Objects.toString(value, "").trim();
    }

    private record SourceCandidate(String title, String url, String domain) {
    }
}
