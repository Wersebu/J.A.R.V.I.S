package com.jarvis.tools.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * SearXNG HTTP implementation of web search.
 */
@Service
public class SearxngWebSearchClient implements WebSearchClient {

    private final WebSearchProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    /**
     * Creates the SearXNG client.
     *
     * @param properties web search properties
     * @param objectMapper JSON mapper
     */
    public SearxngWebSearchClient(WebSearchProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
    }

    @Override
    public WebSearchResponse search(String query, int maxResults) {
        return search(new WebSearchRequest(query, maxResults, "", 1, "", "", ""));
    }

    @Override
    public WebSearchResponse search(WebSearchRequest searchRequest) {
        if (!properties.isEnabled()) {
            throw new WebSearchException("Web search is disabled");
        }
        String normalizedQuery = searchRequest == null || searchRequest.query() == null ? "" : searchRequest.query().strip();
        if (normalizedQuery.isBlank()) {
            throw new WebSearchException("Web search query is required");
        }
        int limit = properties.cappedMaxResults(searchRequest.maxResults());
        long started = System.nanoTime();
        HttpRequest request = HttpRequest.newBuilder(searchUri(searchRequest, normalizedQuery))
                .timeout(properties.readTimeout())
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            long durationMs = (System.nanoTime() - started) / 1_000_000L;
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new WebSearchException("SearXNG returned HTTP " + response.statusCode());
            }
            return new WebSearchResponse(normalizedQuery, normalize(response.body(), limit), durationMs);
        } catch (IOException exception) {
            throw new WebSearchException("SearXNG request failed for " + properties.baseUrl()
                    + ": " + exception.getClass().getSimpleName(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new WebSearchException("SearXNG request interrupted for " + properties.baseUrl(), exception);
        } catch (RuntimeException exception) {
            if (exception instanceof WebSearchException webSearchException) {
                throw webSearchException;
            }
            throw new WebSearchException("SearXNG response could not be parsed from " + properties.baseUrl(), exception);
        }
    }

    private URI searchUri(WebSearchRequest request, String query) {
        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
        StringBuilder uri = new StringBuilder(properties.baseUrl())
                .append("/search?q=").append(encodedQuery)
                .append("&format=json");
        append(uri, "language", request.language());
        append(uri, "time_range", request.timeRange());
        append(uri, "categories", request.category());
        if (request.page() > 1) {
            append(uri, "pageno", String.valueOf(request.page()));
        }
        return URI.create(uri.toString());
    }

    private void append(StringBuilder uri, String key, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        uri.append('&')
                .append(URLEncoder.encode(key, StandardCharsets.UTF_8))
                .append('=')
                .append(URLEncoder.encode(value.strip(), StandardCharsets.UTF_8));
    }

    private List<WebSearchResult> normalize(String body, int limit) {
        try {
            JsonNode root = objectMapper.readTree(body == null ? "" : body);
            JsonNode results = root.path("results");
            if (!results.isArray()) {
                return List.of();
            }
            List<WebSearchResult> normalized = new ArrayList<>();
            for (JsonNode result : results) {
                if (normalized.size() >= limit) {
                    break;
                }
                String url = text(result, "url");
                if (url.isBlank()) {
                    continue;
                }
                normalized.add(new WebSearchResult(
                        firstNonBlank(text(result, "title"), url),
                        url,
                        abbreviate(firstNonBlank(text(result, "content"), text(result, "snippet"))),
                        firstNonBlank(text(result, "engine"), firstEngine(result.path("engines")), host(url))
                ));
            }
            return normalized;
        } catch (IOException exception) {
            throw new WebSearchException("Invalid SearXNG JSON response", exception);
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("");
    }

    private String firstEngine(JsonNode engines) {
        if (!engines.isArray() || engines.isEmpty()) {
            return "";
        }
        return engines.get(0).asText("");
    }

    private String host(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? "" : host.toLowerCase(Locale.ROOT);
        } catch (RuntimeException exception) {
            return "";
        }
    }

    private String abbreviate(String value) {
        String compact = value == null ? "" : value.replaceAll("\\s+", " ").strip();
        int max = properties.snippetMaxLength();
        return compact.length() <= max ? compact : compact.substring(0, max).strip() + "...";
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return "";
    }
}
