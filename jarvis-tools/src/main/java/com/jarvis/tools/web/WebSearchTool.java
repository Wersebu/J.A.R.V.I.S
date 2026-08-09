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
import java.util.HashMap;
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

    private final WebSearchClient webSearchClient;
    private final WebSearchProperties properties;
    private final CognitiveEventBus cognitiveEventBus;

    /**
     * Creates the web search tool.
     *
     * @param webSearchClient search client
     * @param properties web search properties
     * @param cognitiveEventBus event bus
     */
    public WebSearchTool(
            WebSearchClient webSearchClient,
            WebSearchProperties properties,
            CognitiveEventBus cognitiveEventBus
    ) {
        this.webSearchClient = webSearchClient;
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
                        arg("maxResults", false, "Maximum normalized results to return."))
        ));
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        String operation = operation(request);
        String query = arg(request, "query");
        int maxResults = intArg(request, "maxResults");
        int effectiveMaxResults = properties.cappedMaxResults(maxResults);
        long started = System.nanoTime();
        LOGGER.info("[WEB_SEARCH] query=\"{}\" maxResults={}", query, effectiveMaxResults);
        publish(request, CognitiveEventType.TOOL_STARTED, "STARTED", "WebSearchTool started", null,
                Map.of("tool", TOOL_NAME, "operation", operation));
        publish(request, CognitiveEventType.SEARCH_STARTED, "SEARCHING", "Web search started", null,
                Map.of("tool", TOOL_NAME, "operation", operation, "query", query, "maxResults", effectiveMaxResults));
        try {
            WebSearchResponse response = webSearchClient.search(query, effectiveMaxResults);
            for (WebSearchResult result : response.results()) {
                publish(request, CognitiveEventType.SEARCH_RESULT, "FOUND", "Web search result", nodeId(result),
                        Map.of("tool", TOOL_NAME, "operation", operation, "title", result.title(), "url", result.url(),
                                "snippet", result.snippet(), "source", result.source()));
            }
            long durationMs = (System.nanoTime() - started) / 1_000_000L;
            Map<String, Object> data = resultData(response, effectiveMaxResults);
            publish(request, CognitiveEventType.SEARCH_FINISHED, "FINISHED", "Web search finished", null,
                    Map.of("tool", TOOL_NAME, "operation", operation, "query", query, "resultsReturned",
                            response.results().size(), "durationMs", durationMs));
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
        return new ToolArgumentDefinition(name, "maxResults".equals(name) ? "number" : "string", required, description);
    }

    private String operation(ToolRequest request) {
        if (request == null) {
            throw new ToolException("Tool request is required");
        }
        String operation = request.operation() == null ? "" : request.operation().trim().toUpperCase(Locale.ROOT);
        if (!SEARCH_WEB.equals(operation)) {
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
                "source", result.source()
        );
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
