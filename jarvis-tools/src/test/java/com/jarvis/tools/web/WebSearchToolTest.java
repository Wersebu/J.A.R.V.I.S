package com.jarvis.tools.web;

import com.jarvis.common.event.CognitiveEvent;
import com.jarvis.common.event.CognitiveEventBus;
import com.jarvis.common.event.CognitiveEventType;
import com.jarvis.common.ai.BrainType;
import com.jarvis.tools.ToolRequest;
import com.jarvis.tools.ToolResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for the WebSearchTool root-cause fix: a plain SEARCH_WEB call must never be
 * silently reinterpreted as a marketplace search, regardless of query wording. Marketplace
 * listing collection only ever runs through the explicit SEARCH_MARKETPLACE operation.
 */
class WebSearchToolTest {

    private static final WebSearchProperties PROPERTIES = new WebSearchProperties(
            true, "http://127.0.0.1:8888", 5, 8, 15, 4, 5, 3, 15, 320, 8000,
            Duration.ofSeconds(2), Duration.ofSeconds(8)
    );

    @Test
    void searchWebNeverInjectsMarketplaceQueriesEvenWithMarketWordInQuery() {
        RecordingSearchClient client = new RecordingSearchClient(List.of(
                new WebSearchResult("NBP - Kursy walut", "https://nbp.pl/kursy", "USD 3.95", "nbp.pl", ""),
                new WebSearchResult("Bankier.pl kursy walut", "https://bankier.pl/waluty", "EUR 4.30", "bankier.pl", "")
        ));
        WebSearchTool tool = new WebSearchTool(client, new StubPageReader(), PROPERTIES, new NoopCognitiveEventBus());

        ToolResult result = tool.execute(new ToolRequest("web", "SEARCH_WEB", "conversation-1", "request-1",
                "reason", "", Map.of("query", "USD PLN EUR PLN exchange rate market")));

        assertThat(result.success()).isTrue();
        assertThat(client.callCount()).isEqualTo(1);
        assertThat(result.data()).doesNotContainKey("marketplaceResearch");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) result.data().get("results");
        assertThat(results).extracting(map -> map.get("domain"))
                .containsExactlyInAnyOrder("nbp.pl", "bankier.pl");
        assertThat(results).allSatisfy(map -> {
            assertThat(map).containsKeys("rank", "canonicalUrl", "domain", "sourceEngine", "score");
        });
    }

    @Test
    void searchMarketplaceInjectsSiteRestrictedQueries() {
        RecordingSearchClient client = new RecordingSearchClient(List.of());
        WebSearchTool tool = new WebSearchTool(client, new StubPageReader(), PROPERTIES, new NoopCognitiveEventBus());

        tool.execute(new ToolRequest("web", "SEARCH_MARKETPLACE", "conversation-1", "request-2",
                "reason", "", Map.of("query", "RTX 3060 12GB", "targetCount", 3)));

        assertThat(client.callCount()).isGreaterThan(1);
        assertThat(client.queries()).anySatisfy(query -> assertThat(query).contains("site:olx.pl"));
    }

    @Test
    void deduplicatesResultsByCanonicalUrl() {
        RecordingSearchClient client = new RecordingSearchClient(List.of(
                new WebSearchResult("A", "https://example.com/page", "snippet a", "example.com", ""),
                new WebSearchResult("A duplicate", "https://example.com/page", "snippet a dup", "example.com", ""),
                new WebSearchResult("B", "https://other.com/x", "snippet b", "other.com", "")
        ));
        WebSearchTool tool = new WebSearchTool(client, new StubPageReader(), PROPERTIES, new NoopCognitiveEventBus());

        ToolResult result = tool.execute(new ToolRequest("web", "SEARCH_WEB", "conversation-1", "request-3",
                "reason", "", Map.of("query", "test")));

        assertThat(result.data().get("resultsReturned")).isEqualTo(2);
    }

    @Test
    void readWebPageReturnsStructuredHttpErrorCode() {
        WebPageReader failingReader = url -> {
            throw new WebSearchException("Web page returned HTTP 410", 410);
        };
        WebSearchTool tool = new WebSearchTool(new RecordingSearchClient(List.of()), failingReader, PROPERTIES, new NoopCognitiveEventBus());

        ToolResult result = tool.execute(new ToolRequest("web", "READ_WEB_PAGE", "conversation-1", "request-4",
                "reason", "", Map.of("url", "https://olx.pl/d/oferta/dead-listing")));

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("HTTP_410");
        assertThat(result.data().get("statusCode")).isEqualTo(410);
    }

    @Test
    void sequentialMarketplaceThenGeneralSearchDoNotContaminateEachOther() {
        RecordingSearchClient client = new RecordingSearchClient(List.of(
                new WebSearchResult("USD/PLN", "https://nbp.pl/kursy", "USD 3.95", "nbp.pl", "")
        ));
        WebSearchTool tool = new WebSearchTool(client, new StubPageReader(), PROPERTIES, new NoopCognitiveEventBus());

        tool.execute(new ToolRequest("web", "SEARCH_MARKETPLACE", "conversation-1", "request-5",
                "reason", "", Map.of("query", "RTX 3060 12GB")));
        client.reset();

        ToolResult second = tool.execute(new ToolRequest("web", "SEARCH_WEB", "conversation-2", "request-6",
                "reason", "", Map.of("query", "kurs USD PLN")));

        assertThat(client.callCount()).isEqualTo(1);
        assertThat(second.data()).doesNotContainKey("marketplaceResearch");
    }

    private static final class RecordingSearchClient implements WebSearchClient {

        private final List<WebSearchResult> canned;
        private final List<String> queries = new ArrayList<>();
        private final AtomicInteger calls = new AtomicInteger();

        private RecordingSearchClient(List<WebSearchResult> canned) {
            this.canned = canned;
        }

        int callCount() {
            return calls.get();
        }

        List<String> queries() {
            return queries;
        }

        void reset() {
            queries.clear();
            calls.set(0);
        }

        @Override
        public WebSearchResponse search(String query, int maxResults) {
            return search(new WebSearchRequest(query, maxResults, "", 1, "", "", ""));
        }

        @Override
        public WebSearchResponse search(WebSearchRequest request) {
            calls.incrementAndGet();
            queries.add(request.query());
            return new WebSearchResponse(request.query(), canned, 5);
        }
    }

    private static final class StubPageReader implements WebPageReader {
        @Override
        public WebPageContent read(String url) {
            return new WebPageContent(url, "title", "text", 4, false, 5);
        }
    }

    private static final class NoopCognitiveEventBus implements CognitiveEventBus {

        @Override
        public void startRequest(String requestId, String conversationId, java.util.function.Consumer<CognitiveEvent> sink) {
        }

        @Override
        public void finishRequest() {
        }

        @Override
        public void updateBrain(BrainType brain, String model) {
        }

        @Override
        public void publish(CognitiveEventType event, String status, String message, String nodeId, Map<String, Object> metadata) {
        }
    }
}
