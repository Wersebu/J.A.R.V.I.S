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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Native tool for current web search through local SearXNG.
 *
 * <p>{@code SEARCH_WEB} and {@code READ_WEB_PAGE} are general-purpose, model-owned capabilities.
 * The result of a plain {@code SEARCH_WEB} call never changes which operation is executed —
 * it is normalized, deduplicated evidence, nothing more. Marketplace listing verification only
 * ever runs when the model explicitly calls {@code SEARCH_MARKETPLACE}.
 */
@Service
public class WebSearchTool implements JarvisTool, ToolSchemaProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(WebSearchTool.class);
    private static final String TOOL_NAME = "web";
    private static final String SEARCH_WEB = "SEARCH_WEB";
    private static final String READ_WEB_PAGE = "READ_WEB_PAGE";
    private static final String SEARCH_MARKETPLACE = "SEARCH_MARKETPLACE";
    private static final Set<String> MARKETPLACE_DOMAINS = Set.of("olx.pl", "allegro.pl", "ceneo.pl", "x-kom.pl", "morele.net");

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
        return "Searches public internet information through local self-hosted SearXNG. Use for public web facts, documentation, news, prices, marketplace offers, and public URL reading. Do not use to inspect live state inside a connected application, editor, database, or MCP runtime.";
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(TOOL_NAME, getDescription(), List.of(
                operation(SEARCH_WEB,
                        "General-purpose live web search. Use for current facts, news, documentation, prices of a "
                                + "single named thing, exchange/commodity rates, or any external source lookup that is not "
                                + "about browsing real marketplace offers/listings. Do not use for live state inside a connected "
                                + "runtime/application; use that provider's MCP tools instead. Never returns marketplace listings.",
                        false, ToolSafetyLevel.READ,
                        arg("query", true, "Search query sent to SearXNG. Used as-is; only technical normalization is applied."),
                        arg("maxResults", false, "Maximum normalized results to return."),
                        arg("profile", false, "Optional research profile: GENERAL, CURRENT_FACT, or NEWS."),
                        arg("language", false, "Optional SearXNG language."),
                        arg("timeRange", false, "Optional SearXNG time range: day, month, or year."),
                        arg("category", false, "Optional SearXNG category.")),
                operation(READ_WEB_PAGE,
                        "Fetch and read normalized visible text from one exact public http/https URL. Use after "
                                + "SEARCH_WEB or SEARCH_MARKETPLACE when a snippet does not contain enough detail, price, "
                                + "or source evidence, or when the user gave you an exact URL to read.",
                        false, ToolSafetyLevel.READ,
                        arg("url", true, "Public http/https URL returned by a previous search or given by the user.")),
                operation(SEARCH_MARKETPLACE,
                        "Find and verify REAL, currently-live marketplace product listings/offers with concrete prices "
                                + "from known marketplace sites (OLX, Allegro, Ceneo, x-kom, Morele). Use only when the user "
                                + "explicitly wants to browse, buy, or compare real for-sale listings/offers. Do not use for "
                                + "currency/exchange rates, news, documentation, or single-fact prices — use SEARCH_WEB for those.",
                        false, ToolSafetyLevel.READ,
                        arg("query", true, "Product description, e.g. \"RTX 3060 12GB\"."),
                        arg("targetCount", false, "How many verified concrete listings to collect (default 5, max 15)."),
                        arg("condition", false, "Requested condition: NEW or USED."),
                        arg("domains", false, "Optional comma-separated marketplace domains to restrict to, e.g. \"olx.pl,allegro.pl\"."))
        ));
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        String operation = operation(request);
        return switch (operation) {
            case READ_WEB_PAGE -> readWebPage(request, operation);
            case SEARCH_MARKETPLACE -> searchMarketplace(request, operation);
            default -> searchWeb(request, operation);
        };
    }

    // ---------------------------------------------------------------------
    // SEARCH_WEB — general purpose, never reinterpreted as marketplace
    // ---------------------------------------------------------------------

    private ToolResult searchWeb(ToolRequest request, String operation) {
        String query = arg(request, "query");
        int maxResults = intArg(request, "maxResults");
        String profile = profile(request);
        int effectiveMaxResults = properties.cappedMaxResults(maxResults > 0 ? maxResults : defaultMaxResults(profile));
        long started = System.nanoTime();
        LOGGER.info("[WEB_TOOL] requestId={} conversationId={} tool={} query=\"{}\" mode={} provider=searxng",
                request.requestId(), request.conversationId(), operation, query, profile);
        publish(request, CognitiveEventType.TOOL_STARTED, "STARTED", "WebSearchTool started", null,
                Map.of("tool", TOOL_NAME, "operation", operation));
        publish(request, CognitiveEventType.SEARCH_STARTED, "SEARCHING", "Web search started", null,
                Map.of("tool", TOOL_NAME, "operation", operation, "query", query, "maxResults", effectiveMaxResults,
                        "profile", profile));
        try {
            WebSearchRequest searchRequest = new WebSearchRequest(
                    query, effectiveMaxResults, arg(request, "language"), 1,
                    arg(request, "timeRange"), arg(request, "category"), profile
            );
            WebSearchResponse response = webSearchClient.search(searchRequest);
            List<WebSearchResult> normalized = normalizeAndDeduplicate(response.results(), effectiveMaxResults);
            long durationMs = (System.nanoTime() - started) / 1_000_000L;
            logResults(request, normalized);
            for (WebSearchResult result : normalized) {
                publish(request, CognitiveEventType.SEARCH_RESULT, "FOUND", "Web search result", nodeId(result),
                        Map.of("tool", TOOL_NAME, "operation", operation, "title", result.title(), "url", result.url(),
                                "snippet", result.snippet(), "source", result.source()));
            }
            Map<String, Object> data = new HashMap<>();
            data.put("query", query);
            data.put("maxResults", effectiveMaxResults);
            data.put("resultsReturned", normalized.size());
            data.put("resultsCount", normalized.size());
            data.put("durationMs", durationMs);
            data.put("provider", "searxng");
            data.put("profile", profile);
            data.put("results", rankedResultMaps(normalized, query));
            publish(request, CognitiveEventType.SEARCH_FINISHED, "FINISHED", "Web search finished", null,
                    Map.of("tool", TOOL_NAME, "operation", operation, "query", query, "resultsReturned",
                            normalized.size(), "durationMs", durationMs, "profile", profile));
            publish(request, CognitiveEventType.TOOL_FINISHED, "FINISHED", "WebSearchTool finished", null,
                    Map.of("tool", TOOL_NAME, "operation", operation, "success", true));
            return new ToolResult(true, TOOL_NAME, operation, request.requestId(), request.conversationId(), false,
                    normalized.stream().map(this::nodeId).toList(), "Web search finished", data, "", "", false, "");
        } catch (WebSearchException exception) {
            return searchFailure(request, operation, query, effectiveMaxResults, profile, started, exception);
        }
    }

    // ---------------------------------------------------------------------
    // SEARCH_MARKETPLACE — explicit, model-selected specialized capability
    // ---------------------------------------------------------------------

    private ToolResult searchMarketplace(ToolRequest request, String operation) {
        String query = arg(request, "query");
        Set<String> domains = restrictedDomains(request);
        int targetCount = clamp(intArg(request, "targetCount"), 1, 15, 5);
        int effectiveMaxResults = properties.cappedMaxResults(properties.marketMaxResults());
        long started = System.nanoTime();
        LOGGER.info("[WEB_TOOL] requestId={} conversationId={} tool={} query=\"{}\" mode=MARKETPLACE provider=searxng",
                request.requestId(), request.conversationId(), operation, query);
        LOGGER.info("[MARKETPLACE_MODE] requestId={} enabled=true source=MODEL_TOOL_REQUEST domains={} targetCount={}",
                request.requestId(), domains, targetCount);
        publish(request, CognitiveEventType.TOOL_STARTED, "STARTED", "WebSearchTool started", null,
                Map.of("tool", TOOL_NAME, "operation", operation));
        publish(request, CognitiveEventType.SEARCH_STARTED, "SEARCHING", "Marketplace search started", null,
                Map.of("tool", TOOL_NAME, "operation", operation, "query", query, "targetCount", targetCount, "domains", domains));
        try {
            Map<String, WebSearchResult> merged = new LinkedHashMap<>();
            addResults(merged, webSearchClient.search(new WebSearchRequest(query, effectiveMaxResults, arg(request, "language"),
                    1, "", "", "MARKET")).results(), domains);
            int attempts = Math.max(1, properties.maxSearchAttempts());
            List<String> queries = marketplaceQueries(query, domains);
            for (int index = 0; index < queries.size() && index < attempts - 1; index++) {
                if (concreteCount(merged) >= Math.min(targetCount, effectiveMaxResults)) {
                    break;
                }
                WebSearchResponse extra = webSearchClient.search(new WebSearchRequest(
                        queries.get(index), effectiveMaxResults, arg(request, "language"), 1, "", "", "MARKET"));
                addResults(merged, extra.results(), domains);
            }
            List<WebSearchResult> ordered = merged.values().stream()
                    .sorted((left, right) -> Integer.compare(resultRank(right.url()), resultRank(left.url())))
                    .limit(effectiveMaxResults)
                    .toList();
            long durationMs = (System.nanoTime() - started) / 1_000_000L;
            logResults(request, ordered);
            for (WebSearchResult result : ordered) {
                publish(request, CognitiveEventType.SEARCH_RESULT, "FOUND", "Marketplace search result", nodeId(result),
                        Map.of("tool", TOOL_NAME, "operation", operation, "title", result.title(), "url", result.url(),
                                "snippet", result.snippet(), "source", result.source()));
            }
            Map<String, Object> data = new HashMap<>();
            data.put("query", query);
            data.put("maxResults", effectiveMaxResults);
            data.put("resultsReturned", ordered.size());
            data.put("resultsCount", ordered.size());
            data.put("durationMs", durationMs);
            data.put("provider", "searxng");
            data.put("profile", "MARKET");
            data.put("marketplaceResearch", true);
            data.put("targetListingCount", targetCount);
            data.put("requestedListingCount", targetCount);
            data.put("results", ordered.stream().map(this::marketplaceResultMap).toList());
            publish(request, CognitiveEventType.SEARCH_FINISHED, "FINISHED", "Marketplace search finished", null,
                    Map.of("tool", TOOL_NAME, "operation", operation, "query", query, "resultsReturned", ordered.size(),
                            "durationMs", durationMs));
            publish(request, CognitiveEventType.TOOL_FINISHED, "FINISHED", "WebSearchTool finished", null,
                    Map.of("tool", TOOL_NAME, "operation", operation, "success", true));
            return new ToolResult(true, TOOL_NAME, operation, request.requestId(), request.conversationId(), false,
                    ordered.stream().map(this::nodeId).toList(), "Marketplace search finished", data, "", "", false, "");
        } catch (WebSearchException exception) {
            return searchFailure(request, operation, query, effectiveMaxResults, "MARKET", started, exception);
        }
    }

    private ToolResult searchFailure(
            ToolRequest request, String operation, String query, int effectiveMaxResults, String profile,
            long started, WebSearchException exception
    ) {
        long durationMs = (System.nanoTime() - started) / 1_000_000L;
        String errorCode = exception.statusCode() > 0 ? "HTTP_" + exception.statusCode() : "WEB_SEARCH_FAILED";
        Map<String, Object> data = Map.of(
                "query", query,
                "maxResults", effectiveMaxResults,
                "profile", profile,
                "durationMs", durationMs,
                "baseUrl", properties.baseUrl(),
                "statusCode", exception.statusCode(),
                "errorCode", errorCode,
                "errorMessage", safe(exception.getMessage())
        );
        publish(request, CognitiveEventType.SEARCH_FINISHED, "FAILED", "Web search failed", null,
                Map.of("tool", TOOL_NAME, "operation", operation, "query", query, "durationMs", durationMs,
                        "baseUrl", properties.baseUrl(), "error", safe(exception.getMessage())));
        publish(request, CognitiveEventType.TOOL_FINISHED, "FAILED", "WebSearchTool failed", null,
                Map.of("tool", TOOL_NAME, "operation", operation, "success", false, "baseUrl", properties.baseUrl(),
                        "error", safe(exception.getMessage())));
        LOGGER.warn("[WEB_TOOL] requestId={} FAILED query=\"{}\" duration={}ms errorCode={} error={}",
                request.requestId(), query, durationMs, errorCode, exception.getMessage());
        return new ToolResult(false, TOOL_NAME, operation, request.requestId(), request.conversationId(), false,
                List.of(), "Web search failed", data, errorCode, safe(exception.getMessage()), false, "");
    }

    // ---------------------------------------------------------------------
    // READ_WEB_PAGE
    // ---------------------------------------------------------------------

    private ToolResult readWebPage(ToolRequest request, String operation) {
        String url = arg(request, "url");
        long started = System.nanoTime();
        LOGGER.info("[WEB_TOOL] requestId={} conversationId={} tool={} url={}",
                request.requestId(), request.conversationId(), operation, url);
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
                    "contentType", content.contentType(),
                    "links", content.links().stream()
                            .map(link -> Map.of("title", link.title(), "url", link.url()))
                            .toList()
            );
            LOGGER.info("[WEB_PAGE_READ] requestId={} url={} finalUrl={} status={} characters={} durationMs={}",
                    request.requestId(), url, content.url(), content.statusCode(), content.characters(), content.durationMs());
            publish(request, CognitiveEventType.DOCUMENT_READ, "READ", "Web page read", nodeId(content.url()),
                    Map.of("tool", TOOL_NAME, "operation", operation, "url", content.url(), "title", content.title(),
                            "characters", content.characters(), "truncated", content.truncated(),
                            "statusCode", content.statusCode(), "contentType", content.contentType(),
                            "links", content.links().size()));
            publish(request, CognitiveEventType.DOCUMENT_READ_FINISHED, "FINISHED", "Web page read finished", nodeId(content.url()),
                    Map.of("tool", TOOL_NAME, "operation", operation, "url", content.url(), "title", content.title(),
                            "characters", content.characters(), "durationMs", content.durationMs(),
                            "statusCode", content.statusCode(), "contentType", content.contentType()));
            publish(request, CognitiveEventType.TOOL_FINISHED, "FINISHED", "WebSearchTool finished", nodeId(content.url()),
                    Map.of("tool", TOOL_NAME, "operation", operation, "success", true, "url", content.url()));
            return new ToolResult(true, TOOL_NAME, operation, request.requestId(), request.conversationId(), false,
                    List.of(nodeId(content.url())), "Web page read finished", data, "", "", false, "");
        } catch (WebSearchException exception) {
            long durationMs = (System.nanoTime() - started) / 1_000_000L;
            int statusCode = exception.statusCode();
            String errorCode = statusCode > 0 ? "HTTP_" + statusCode : "WEB_PAGE_READ_FAILED";
            Map<String, Object> data = Map.of(
                    "url", url,
                    "durationMs", durationMs,
                    "statusCode", statusCode,
                    "errorCode", errorCode,
                    "errorMessage", safe(exception.getMessage())
            );
            LOGGER.warn("[WEB_PAGE_READ] requestId={} FAILED url={} duration={}ms errorCode={} error={}",
                    request.requestId(), url, durationMs, errorCode, exception.getMessage());
            publish(request, CognitiveEventType.DOCUMENT_READ_FINISHED, "FAILED", "Web page read failed", "web:page",
                    Map.of("tool", TOOL_NAME, "operation", operation, "url", url, "durationMs", durationMs,
                            "statusCode", statusCode, "error", safe(exception.getMessage())));
            publish(request, CognitiveEventType.TOOL_FINISHED, "FAILED", "WebSearchTool failed", "web:page",
                    Map.of("tool", TOOL_NAME, "operation", operation, "success", false, "url", url,
                            "error", safe(exception.getMessage())));
            return new ToolResult(false, TOOL_NAME, operation, request.requestId(), request.conversationId(), false,
                    List.of(), "Web page read failed", data, errorCode, safe(exception.getMessage()), false, "");
        }
    }

    // ---------------------------------------------------------------------
    // Shared result normalization (SEARCH_WEB): canonicalize, dedupe, rank, score
    // ---------------------------------------------------------------------

    private List<WebSearchResult> normalizeAndDeduplicate(List<WebSearchResult> raw, int limit) {
        Map<String, WebSearchResult> seen = new LinkedHashMap<>();
        for (WebSearchResult result : raw) {
            String canonical = canonicalUrl(result.url());
            if (canonical.isBlank()) {
                continue;
            }
            seen.putIfAbsent(canonical, result);
        }
        return seen.values().stream().limit(limit).toList();
    }

    private List<Map<String, Object>> rankedResultMaps(List<WebSearchResult> results, String query) {
        Set<String> queryTerms = terms(query);
        List<Map<String, Object>> mapped = new ArrayList<>();
        int rank = 1;
        for (WebSearchResult result : results) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("rank", rank++);
            map.put("title", result.title());
            map.put("url", result.url());
            map.put("canonicalUrl", canonicalUrl(result.url()));
            map.put("domain", WebUrlClassifier.domain(result.url()));
            map.put("snippet", result.snippet());
            map.put("source", result.source());
            map.put("sourceEngine", result.source());
            map.put("publishedAt", result.publishedAt());
            map.put("score", relevanceScore(queryTerms, result));
            map.put("concreteListing", WebUrlClassifier.isConcreteListing(result.url()));
            map.put("searchPage", WebUrlClassifier.isSearchPage(result.url()));
            mapped.add(map);
        }
        return mapped;
    }

    private double relevanceScore(Set<String> queryTerms, WebSearchResult result) {
        if (queryTerms.isEmpty()) {
            return 0.0d;
        }
        String haystack = normalizeAscii(result.title() + " " + result.snippet());
        long matched = queryTerms.stream().filter(haystack::contains).count();
        return Math.round((double) matched / queryTerms.size() * 100.0d) / 100.0d;
    }

    private Set<String> terms(String query) {
        Set<String> terms = new LinkedHashSet<>();
        for (String token : normalizeAscii(query).split("[^a-z0-9]+")) {
            if (token.length() >= 2) {
                terms.add(token);
            }
        }
        return terms;
    }

    private void logResults(ToolRequest request, List<WebSearchResult> results) {
        int rank = 1;
        for (WebSearchResult result : results) {
            LOGGER.info("[WEB_RESULT] requestId={} rank={} domain={} title={} engine={}",
                    request.requestId(), rank++, WebUrlClassifier.domain(result.url()), abbreviate(result.title()), result.source());
        }
    }

    // ---------------------------------------------------------------------
    // Marketplace-only helpers
    // ---------------------------------------------------------------------

    private Map<String, Object> marketplaceResultMap(WebSearchResult result) {
        return Map.of(
                "title", result.title(),
                "url", result.url(),
                "snippet", result.snippet(),
                "source", result.source(),
                "concreteListing", WebUrlClassifier.isConcreteListing(result.url()),
                "searchPage", WebUrlClassifier.isSearchPage(result.url())
        );
    }

    private void addResults(Map<String, WebSearchResult> merged, List<WebSearchResult> results, Set<String> allowedDomains) {
        for (WebSearchResult result : results) {
            String key = canonicalUrl(result.url());
            if (key.isBlank()) {
                continue;
            }
            if (!allowedDomains.isEmpty()) {
                String domain = WebUrlClassifier.domain(result.url());
                boolean allowed = allowedDomains.stream().anyMatch(domain::endsWith);
                if (!allowed) {
                    continue;
                }
            }
            merged.putIfAbsent(key, result);
        }
    }

    private int concreteCount(Map<String, WebSearchResult> merged) {
        int count = 0;
        for (WebSearchResult result : merged.values()) {
            if (WebUrlClassifier.isConcreteListing(result.url())) {
                count++;
            }
        }
        return count;
    }

    private List<String> marketplaceQueries(String query, Set<String> restrictedDomains) {
        String compact = query == null ? "" : query.replaceAll("\\s+", " ").strip();
        if (compact.isBlank()) {
            return List.of();
        }
        Set<String> domains = restrictedDomains.isEmpty() ? MARKETPLACE_DOMAINS : restrictedDomains;
        List<String> queries = new ArrayList<>();
        if (domains.contains("olx.pl")) {
            queries.add("site:olx.pl/d/oferta " + compact);
        }
        if (domains.contains("allegro.pl")) {
            queries.add("site:allegro.pl/oferta " + compact);
        }
        if (domains.contains("ceneo.pl")) {
            queries.add("site:ceneo.pl " + compact + " cena");
        }
        if (domains.contains("x-kom.pl")) {
            queries.add("site:x-kom.pl/p " + compact + " cena");
        }
        queries.add(compact + " " + String.join(" ", domains) + " oferta cena");
        return queries;
    }

    private int resultRank(String url) {
        if (WebUrlClassifier.isConcreteListing(url)) {
            return 3;
        }
        if (WebUrlClassifier.isSearchPage(url)) {
            return 1;
        }
        return 2;
    }

    private Set<String> restrictedDomains(ToolRequest request) {
        String raw = arg(request, "domains");
        if (raw.isBlank()) {
            return Set.of();
        }
        Set<String> domains = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String trimmed = part.strip().toLowerCase(Locale.ROOT);
            if (!trimmed.isBlank()) {
                domains.add(trimmed);
            }
        }
        return domains;
    }

    // ---------------------------------------------------------------------
    // Definition / argument plumbing
    // ---------------------------------------------------------------------

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
        return new ToolArgumentDefinition(name, ("maxResults".equals(name) || "targetCount".equals(name)) ? "number" : "string", required, description);
    }

    private String profile(ToolRequest request) {
        String value = arg(request, "profile").toUpperCase(Locale.ROOT);
        return switch (value) {
            case "CURRENT_FACT", "NEWS" -> value;
            default -> "GENERAL";
        };
    }

    private int defaultMaxResults(String profile) {
        return switch (profile) {
            case "CURRENT_FACT", "NEWS" -> properties.currentFactMaxResults();
            default -> properties.defaultMaxResults();
        };
    }

    private String operation(ToolRequest request) {
        if (request == null) {
            throw new ToolException("Tool request is required");
        }
        String operation = request.operation() == null ? "" : request.operation().trim().toUpperCase(Locale.ROOT);
        if (!SEARCH_WEB.equals(operation) && !READ_WEB_PAGE.equals(operation) && !SEARCH_MARKETPLACE.equals(operation)) {
            throw new ToolException("Unsupported web operation: " + request.operation());
        }
        return operation;
    }

    private String canonicalUrl(String url) {
        try {
            URI uri = new URI(url == null ? "" : url.strip());
            if (uri.getHost() == null) {
                return "";
            }
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
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

    private int clamp(int value, int min, int max, int defaultValue) {
        int effective = value > 0 ? value : defaultValue;
        return Math.max(min, Math.min(max, effective));
    }

    private String normalizeAscii(String value) {
        return java.text.Normalizer.normalize(value == null ? "" : value, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
    }

    private String abbreviate(String value) {
        String compact = value == null ? "" : value.replaceAll("\\s+", " ").strip();
        return compact.length() <= 80 ? compact : compact.substring(0, 80).strip() + "...";
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
