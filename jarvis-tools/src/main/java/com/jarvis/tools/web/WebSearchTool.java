package com.jarvis.tools.web;

import com.jarvis.common.event.CognitiveEventBus;
import com.jarvis.common.event.CognitiveEventType;
import com.jarvis.tools.JarvisTool;
import com.jarvis.tools.ToolException;
import com.jarvis.tools.ToolRequest;
import com.jarvis.tools.ToolResult;
import com.jarvis.tools.schema.ToolArgumentDefinition;
import com.jarvis.tools.schema.ToolDefinition;
import com.jarvis.tools.schema.ToolOperationDefinition;
import com.jarvis.tools.schema.ToolSafetyLevel;
import com.jarvis.tools.schema.ToolSchemaProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Native tool for current web search through local SearXNG.
 */
@Service
public class WebSearchTool implements JarvisTool, ToolSchemaProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(WebSearchTool.class);
    private static final String TOOL_NAME = "web";
    private static final String SEARCH_WEB = "SEARCH_WEB";
    private static final String READ_WEB_PAGE = "READ_WEB_PAGE";

    private final WebSearchClient webSearchClient;
    private final WebPageReader webPageReader;
    private final WebSearchProperties properties;
    private final CognitiveEventBus cognitiveEventBus;

    /**
     * Creates the web search tool.
     *
     * @param webSearchClient search client
     * @param webPageReader web page reader
     * @param properties web search properties
     * @param cognitiveEventBus event bus
     */
    public WebSearchTool(
            WebSearchClient webSearchClient,
            WebPageReader webPageReader,
            WebSearchProperties properties,
            CognitiveEventBus cognitiveEventBus
    ) {
        this.webSearchClient = webSearchClient;
        this.webPageReader = webPageReader;
        this.properties = properties;
        this.cognitiveEventBus = cognitiveEventBus;
    }

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public String getDescription() {
        return "Searches current public web information through local self-hosted SearXNG.";
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(TOOL_NAME, getDescription(), List.of(
                operation(SEARCH_WEB, "Search current internet information using local SearXNG. Use this for current prices, recent facts, news, releases, or external sources.", false, ToolSafetyLevel.READ,
                        arg("query", true, "Search query sent to SearXNG."),
                        arg("maxResults", false, "Maximum normalized results to return."),
                        arg("profile", false, "Research profile: GENERAL, CURRENT_FACT, NEWS, or MARKET."),
                        arg("language", false, "Optional SearXNG language."),
                        arg("page", false, "Optional SearXNG page number."),
                        arg("timeRange", false, "Optional SearXNG time range."),
                        arg("category", false, "Optional SearXNG category.")),
                operation(READ_WEB_PAGE, "Fetch and read normalized visible text from a public http/https search result URL. Use after SEARCH_WEB when snippets do not contain enough detail, prices, facts, or source evidence.", false, ToolSafetyLevel.READ,
                        arg("url", true, "Public http/https URL returned by web search."))
        ));
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        String operation = operation(request);
        if (READ_WEB_PAGE.equals(operation)) {
            return readWebPage(request, operation);
        }
        String query = arg(request, "query");
        int maxResults = intArg(request, "maxResults");
        String profile = profile(request);
        int effectiveMaxResults = properties.cappedMaxResults(maxResults > 0 ? maxResults : defaultMaxResults(profile));
        int page = Math.max(1, intArg(request, "page"));
        long started = System.nanoTime();
        LOGGER.info("[WEB_SEARCH] query=\"{}\" profile={} page={} maxResults={}", query, profile, page, effectiveMaxResults);
        publish(request, CognitiveEventType.TOOL_STARTED, "STARTED", "WebSearchTool started", null,
                Map.of("tool", TOOL_NAME, "operation", operation));
        publish(request, CognitiveEventType.SEARCH_STARTED, "SEARCHING", "Web search started", null,
                Map.of("tool", TOOL_NAME, "operation", operation, "query", query, "maxResults", effectiveMaxResults,
                        "profile", profile, "page", page));
        try {
            WebSearchRequest searchRequest = new WebSearchRequest(
                    query,
                    effectiveMaxResults,
                    arg(request, "language"),
                    page,
                    arg(request, "timeRange"),
                    arg(request, "category"),
                    profile
            );
            WebSearchResponse response = search(searchRequest, effectiveMaxResults);
            for (WebSearchResult result : response.results()) {
                publish(request, CognitiveEventType.SEARCH_RESULT, "FOUND", "Web search result", nodeId(result),
                        Map.of("tool", TOOL_NAME, "operation", operation, "title", result.title(), "url", result.url(),
                                "snippet", result.snippet(), "source", result.source()));
            }
            long durationMs = (System.nanoTime() - started) / 1_000_000L;
            Map<String, Object> data = resultData(response, effectiveMaxResults);
            data.put("profile", profile);
            data.put("page", page);
            publish(request, CognitiveEventType.SEARCH_FINISHED, "FINISHED", "Web search finished", null,
                    Map.of("tool", TOOL_NAME, "operation", operation, "query", query, "resultsReturned",
                            response.results().size(), "durationMs", durationMs, "profile", profile, "page", page));
            publish(request, CognitiveEventType.TOOL_FINISHED, "FINISHED", "WebSearchTool finished", null,
                    Map.of("tool", TOOL_NAME, "operation", operation, "success", true));
            LOGGER.info("[WEB_SEARCH] results={} duration={}ms", response.results().size(), durationMs);
            return new ToolResult(true, TOOL_NAME, operation, request.requestId(), request.conversationId(), false,
                    response.results().stream().map(this::nodeId).toList(), "Web search finished", data, "", "", false, "");
        } catch (WebSearchException exception) {
            long durationMs = (System.nanoTime() - started) / 1_000_000L;
            Map<String, Object> data = Map.of(
                    "query", query,
                    "maxResults", effectiveMaxResults,
                    "profile", profile,
                    "page", page,
                    "durationMs", durationMs,
                    "baseUrl", properties.baseUrl(),
                    "errorCode", "WEB_SEARCH_FAILED",
                    "errorMessage", exception.getMessage()
            );
            publish(request, CognitiveEventType.SEARCH_FINISHED, "FAILED", "Web search failed", null,
                    Map.of("tool", TOOL_NAME, "operation", operation, "query", query, "durationMs", durationMs,
                            "baseUrl", properties.baseUrl(), "error", exception.getMessage()));
            publish(request, CognitiveEventType.TOOL_FINISHED, "FAILED", "WebSearchTool failed", null,
                    Map.of("tool", TOOL_NAME, "operation", operation, "success", false, "baseUrl", properties.baseUrl(),
                            "error", exception.getMessage()));
            LOGGER.warn("[WEB_SEARCH] FAILED query=\"{}\" duration={}ms error={}", query, durationMs, exception.getMessage());
            return new ToolResult(false, TOOL_NAME, operation, request.requestId(), request.conversationId(), false,
                    List.of(), "Web search failed", data, "WEB_SEARCH_FAILED", exception.getMessage(), false, "");
        }
    }

    private ToolResult readWebPage(ToolRequest request, String operation) {
        String url = arg(request, "url");
        long started = System.nanoTime();
        LOGGER.info("[WEB_PAGE_READ] url={}", url);
        publish(request, CognitiveEventType.TOOL_STARTED, "STARTED", "WebSearchTool started", "web:page",
                Map.of("tool", TOOL_NAME, "operation", operation, "url", url));
        publish(request, CognitiveEventType.DOCUMENT_READ_STARTED, "READING", "Web page read started", "web:page",
                Map.of("tool", TOOL_NAME, "operation", operation, "url", url));
        try {
            WebPageContent content = webPageReader.read(url);
            Map<String, Object> data = Map.of(
                    "url", content.url(),
                    "title", content.title(),
                    "content", content.text(),
                    "characters", content.characters(),
                    "truncated", content.truncated(),
                    "durationMs", content.durationMs(),
                    "statusCode", content.statusCode(),
                    "contentType", content.contentType()
            );
            publish(request, CognitiveEventType.DOCUMENT_READ, "READ", "Web page read", nodeId(content.url()),
                    Map.of("tool", TOOL_NAME, "operation", operation, "url", content.url(), "title", content.title(),
                            "characters", content.characters(), "truncated", content.truncated(),
                            "statusCode", content.statusCode(), "contentType", content.contentType()));
            publish(request, CognitiveEventType.DOCUMENT_READ_FINISHED, "FINISHED", "Web page read finished", nodeId(content.url()),
                    Map.of("tool", TOOL_NAME, "operation", operation, "url", content.url(), "title", content.title(),
                            "characters", content.characters(), "durationMs", content.durationMs(),
                            "statusCode", content.statusCode(), "contentType", content.contentType()));
            publish(request, CognitiveEventType.TOOL_FINISHED, "FINISHED", "WebSearchTool finished", nodeId(content.url()),
                    Map.of("tool", TOOL_NAME, "operation", operation, "success", true, "url", content.url()));
            LOGGER.info("[WEB_PAGE_READ] characters={} duration={}ms", content.characters(), content.durationMs());
            return new ToolResult(true, TOOL_NAME, operation, request.requestId(), request.conversationId(), false,
                    List.of(nodeId(content.url())), "Web page read finished", data, "", "", false, "");
        } catch (WebSearchException exception) {
            long durationMs = (System.nanoTime() - started) / 1_000_000L;
            Map<String, Object> data = Map.of(
                    "url", url,
                    "durationMs", durationMs,
                    "errorCode", "WEB_PAGE_READ_FAILED",
                    "errorMessage", exception.getMessage()
            );
            publish(request, CognitiveEventType.DOCUMENT_READ_FINISHED, "FAILED", "Web page read failed", "web:page",
                    Map.of("tool", TOOL_NAME, "operation", operation, "url", url, "durationMs", durationMs,
                            "error", exception.getMessage()));
            publish(request, CognitiveEventType.TOOL_FINISHED, "FAILED", "WebSearchTool failed", "web:page",
                    Map.of("tool", TOOL_NAME, "operation", operation, "success", false, "url", url,
                            "error", exception.getMessage()));
            LOGGER.warn("[WEB_PAGE_READ] FAILED url={} duration={}ms error={}", url, durationMs, exception.getMessage());
            return new ToolResult(false, TOOL_NAME, operation, request.requestId(), request.conversationId(), false,
                    List.of(), "Web page read failed", data, "WEB_PAGE_READ_FAILED", exception.getMessage(), false, "");
        }
    }

    private ToolOperationDefinition operation(
            String name,
            String description,
            boolean write,
            ToolSafetyLevel safetyLevel,
            ToolArgumentDefinition... arguments
    ) {
        return new ToolOperationDefinition(name, description, List.of(arguments), write, safetyLevel);
    }

    private ToolArgumentDefinition arg(String name, boolean required, String description) {
        return new ToolArgumentDefinition(name, ("maxResults".equals(name) || "page".equals(name)) ? "number" : "string", required, description);
    }

    private String profile(ToolRequest request) {
        String value = arg(request, "profile").toUpperCase(Locale.ROOT);
        return switch (value) {
            case "CURRENT_FACT", "NEWS", "MARKET" -> value;
            default -> marketLike(arg(request, "query")) ? "MARKET" : "GENERAL";
        };
    }

    private int defaultMaxResults(String profile) {
        return switch (profile) {
            case "MARKET" -> properties.marketMaxResults();
            case "CURRENT_FACT", "NEWS" -> properties.currentFactMaxResults();
            default -> properties.defaultMaxResults();
        };
    }

    private boolean marketLike(String query) {
        String normalized = query == null ? "" : query.toLowerCase(Locale.ROOT);
        return normalized.matches(".*\\b(cena|ceny|koszt|kosztuje|price|prices|market|uzywan|used|wtorn)\\w*\\b.*");
    }

    private String operation(ToolRequest request) {
        if (request == null) {
            throw new ToolException("Tool request is required");
        }
        String operation = request.operation() == null ? "" : request.operation().trim().toUpperCase(Locale.ROOT);
        if (!SEARCH_WEB.equals(operation) && !READ_WEB_PAGE.equals(operation)) {
            throw new ToolException("Unsupported web operation: " + request.operation());
        }
        return operation;
    }

    private Map<String, Object> resultData(WebSearchResponse response, int maxResults) {
        Map<String, Object> data = new HashMap<>();
        data.put("query", response.query());
        data.put("maxResults", maxResults);
        data.put("resultsReturned", response.results().size());
        data.put("durationMs", response.durationMs());
        data.put("results", response.results().stream().map(this::resultMap).toList());
        return data;
    }

    private Map<String, Object> resultMap(WebSearchResult result) {
        return Map.of(
                "title", result.title(),
                "url", result.url(),
                "snippet", result.snippet(),
                "source", result.source(),
                "concreteListing", concreteListing(result.url()),
                "searchPage", searchPage(result.url())
        );
    }

    private WebSearchResponse search(WebSearchRequest request, int effectiveMaxResults) {
        WebSearchResponse primary = webSearchClient.search(request);
        if (!marketplaceResearch(request)) {
            return primary;
        }

        Map<String, WebSearchResult> merged = new LinkedHashMap<>();
        addResults(merged, primary.results());
        int attempts = Math.max(1, properties.maxSearchAttempts());
        List<String> queries = marketplaceQueries(request.query());
        for (int index = 0; index < queries.size() && index < attempts - 1; index++) {
            if (concreteCount(merged) >= Math.min(5, effectiveMaxResults)) {
                break;
            }
            WebSearchResponse extra = webSearchClient.search(new WebSearchRequest(
                    queries.get(index),
                    effectiveMaxResults,
                    request.language(),
                    1,
                    request.timeRange(),
                    request.category(),
                    request.profile()
            ));
            addResults(merged, extra.results());
        }

        List<WebSearchResult> ordered = merged.values().stream()
                .sorted((left, right) -> Integer.compare(resultRank(right.url()), resultRank(left.url())))
                .limit(effectiveMaxResults)
                .toList();
        return new WebSearchResponse(primary.query(), ordered, primary.durationMs());
    }

    private void addResults(Map<String, WebSearchResult> merged, List<WebSearchResult> results) {
        for (WebSearchResult result : results) {
            String key = canonicalUrl(result.url());
            if (key.isBlank()) {
                continue;
            }
            merged.putIfAbsent(key, result);
        }
    }

    private int concreteCount(Map<String, WebSearchResult> merged) {
        int count = 0;
        for (WebSearchResult result : merged.values()) {
            if (concreteListing(result.url())) {
                count++;
            }
        }
        return count;
    }

    private boolean marketplaceResearch(WebSearchRequest request) {
        String query = request == null ? "" : request.query();
        String profile = request == null ? "" : request.profile();
        String normalized = query == null ? "" : query.toLowerCase(Locale.ROOT);
        return "MARKET".equalsIgnoreCase(profile)
                || marketLike(query)
                || normalized.matches(".*\\b(olx|allegro|ofert|ogloszen|ogloszenie|listing|link)\\w*\\b.*");
    }

    private List<String> marketplaceQueries(String query) {
        String compact = query == null ? "" : query.replaceAll("\\s+", " ").strip();
        if (compact.isBlank()) {
            return List.of();
        }
        List<String> queries = new ArrayList<>();
        queries.add("site:olx.pl/d/oferta " + compact);
        queries.add("site:allegro.pl/oferta " + compact);
        queries.add(compact + " OLX oferta cena");
        queries.add(compact + " Allegro oferta cena");
        return queries;
    }

    private int resultRank(String url) {
        if (concreteListing(url)) {
            return 3;
        }
        if (searchPage(url)) {
            return 1;
        }
        return 2;
    }

    private boolean concreteListing(String url) {
        try {
            URI uri = new URI(url == null ? "" : url.strip());
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
            if (host.endsWith("olx.pl")) {
                return path.contains("/d/oferta/") || path.contains("/oferta/");
            }
            if (host.endsWith("allegro.pl")) {
                return path.contains("/oferta/");
            }
            return false;
        } catch (URISyntaxException exception) {
            return false;
        }
    }

    private boolean searchPage(String url) {
        try {
            URI uri = new URI(url == null ? "" : url.strip());
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
            String query = uri.getQuery() == null ? "" : uri.getQuery().toLowerCase(Locale.ROOT);
            if (host.endsWith("olx.pl")) {
                return path.contains("/q-") || query.contains("search") || query.contains("q=");
            }
            if (host.endsWith("allegro.pl")) {
                return path.startsWith("/listing") || query.contains("string=");
            }
            return false;
        } catch (URISyntaxException exception) {
            return false;
        }
    }

    private String canonicalUrl(String url) {
        try {
            URI uri = new URI(url == null ? "" : url.strip());
            if (uri.getHost() == null) {
                return "";
            }
            return uri.normalize().toString();
        } catch (URISyntaxException exception) {
            return "";
        }
    }

    private void publish(
            ToolRequest request,
            CognitiveEventType eventType,
            String status,
            String message,
            String nodeId,
            Map<String, Object> metadata
    ) {
        Map<String, Object> values = new HashMap<>(metadata == null ? Map.of() : metadata);
        values.put("requestId", safe(request.requestId()));
        values.put("conversationId", safe(request.conversationId()));
        values.put("timestamp", Instant.now().toString());
        cognitiveEventBus.publish(eventType, status, message, nodeId, values);
        cognitiveEventBus.publishBackground(request.requestId(), request.conversationId(), eventType, status, message, nodeId, values);
    }

    private String nodeId(WebSearchResult result) {
        return "web:" + Integer.toHexString(result.url().hashCode());
    }

    private String nodeId(String url) {
        return "web:" + Integer.toHexString(safe(url).hashCode());
    }

    private String arg(ToolRequest request, String name) {
        Object value = request.arguments().get(name);
        return value == null ? "" : String.valueOf(value).strip();
    }

    private int intArg(ToolRequest request, String name) {
        Object value = request.arguments().get(name);
        if (value == null) {
            return 0;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
