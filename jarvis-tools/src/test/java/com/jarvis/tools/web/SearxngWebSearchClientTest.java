package com.jarvis.tools.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SearxngWebSearchClientTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void returnsNormalizedSearchResults() throws IOException {
        startServer(200, """
                {"results":[
                  {"title":"RTX 4060 Ti 16GB","url":"https://example.com/gpu","content":"Cena karty graficznej","engine":"duckduckgo"}
                ]}
                """);

        WebSearchResponse response = client(defaultProperties()).search("RTX 4060 Ti", 5);

        assertThat(response.query()).isEqualTo("RTX 4060 Ti");
        assertThat(response.results()).hasSize(1);
        assertThat(response.results().getFirst().title()).isEqualTo("RTX 4060 Ti 16GB");
        assertThat(response.results().getFirst().url()).isEqualTo("https://example.com/gpu");
        assertThat(response.results().getFirst().snippet()).isEqualTo("Cena karty graficznej");
        assertThat(response.results().getFirst().source()).isEqualTo("duckduckgo");
    }

    @Test
    void returnsEmptyListWhenSearxngHasNoResults() throws IOException {
        startServer(200, "{\"results\":[]}");

        WebSearchResponse response = client(defaultProperties()).search("no results", 5);

        assertThat(response.results()).isEmpty();
    }

    @Test
    void failsWhenSearxngIsUnavailable() {
        WebSearchProperties properties = new WebSearchProperties(true, "http://127.0.0.1:1", 5, 10, 320, 8000,
                Duration.ofMillis(100), Duration.ofMillis(100));

        assertThatThrownBy(() -> client(properties).search("RTX", 5))
                .isInstanceOf(WebSearchException.class)
                .hasMessageContaining("SearXNG request failed");
    }

    @Test
    void failsOnTimeout() throws IOException {
        startServer(exchange -> {
            try {
                Thread.sleep(400);
                write(exchange, 200, "{\"results\":[]}");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        WebSearchProperties properties = new WebSearchProperties(true, baseUrl(), 5, 10, 320, 8000,
                Duration.ofMillis(100), Duration.ofMillis(100));

        assertThatThrownBy(() -> client(properties).search("slow", 5))
                .isInstanceOf(WebSearchException.class)
                .hasMessageContaining("SearXNG request failed");
    }

    @Test
    void failsOnInvalidJson() throws IOException {
        startServer(200, "not-json");

        assertThatThrownBy(() -> client(defaultProperties()).search("bad json", 5))
                .isInstanceOf(WebSearchException.class)
                .hasMessageContaining("Invalid SearXNG JSON response");
    }

    @Test
    void capsMaxResults() throws IOException {
        startServer(200, """
                {"results":[
                  {"title":"A","url":"https://example.com/a","content":"A"},
                  {"title":"B","url":"https://example.com/b","content":"B"},
                  {"title":"C","url":"https://example.com/c","content":"C"}
                ]}
                """);
        WebSearchProperties properties = new WebSearchProperties(true, baseUrl(), 5, 2, 320, 8000,
                Duration.ofSeconds(1), Duration.ofSeconds(1));

        WebSearchResponse response = client(properties).search("many", 50);

        assertThat(response.results()).extracting(WebSearchResult::title).containsExactly("A", "B");
    }

    @Test
    void truncatesSnippetsAndFallsBackToHostSource() throws IOException {
        startServer(200, """
                {"results":[
                  {"title":"Long","url":"https://docs.example.com/article","content":"abcdefghijk"}
                ]}
                """);
        WebSearchProperties properties = new WebSearchProperties(true, baseUrl(), 5, 10, 5, 8000,
                Duration.ofSeconds(1), Duration.ofSeconds(1));

        WebSearchResponse response = client(properties).search("long", 5);

        assertThat(response.results().getFirst().snippet()).isEqualTo("abcde...");
        assertThat(response.results().getFirst().source()).isEqualTo("docs.example.com");
    }

    @Test
    void encodesSearchQueryAsUriSafeUtf8() throws IOException {
        AtomicReference<String> rawQuery = new AtomicReference<>();
        AtomicReference<String> userAgent = new AtomicReference<>();
        startServer(exchange -> {
            rawQuery.set(exchange.getRequestURI().getRawQuery());
            userAgent.set(exchange.getRequestHeaders().getFirst("User-Agent"));
            write(exchange, 200, "{\"results\":[]}");
        });

        client(defaultProperties()).search("RTX 3060 Aorus używane OLX", 5);

        assertThat(rawQuery.get()).contains("q=RTX%203060%20Aorus%20u%C5%BCywane%20OLX");
        assertThat(rawQuery.get()).contains("format=json");
        assertThat(userAgent.get()).isEqualTo("JARVIS-Core-WebSearch/2.7");
    }

    @Test
    void retriesMinimalSearchWhenSearxngRejectsOptionalParameters() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> firstQuery = new AtomicReference<>();
        AtomicReference<String> secondQuery = new AtomicReference<>();
        startServer(exchange -> {
            if (calls.incrementAndGet() == 1) {
                firstQuery.set(exchange.getRequestURI().getRawQuery());
                write(exchange, 400, "{\"error\":\"bad category\"}");
                return;
            }
            secondQuery.set(exchange.getRequestURI().getRawQuery());
            write(exchange, 200, """
                    {"results":[
                      {"title":"Fallback result","url":"https://example.com/fallback","content":"OK"}
                    ]}
                    """);
        });

        WebSearchRequest request = new WebSearchRequest("RTX 3060", 5, "pl", 2, "day", "shopping", "MARKET");
        WebSearchResponse response = client(defaultProperties()).search(request);

        assertThat(calls.get()).isEqualTo(2);
        assertThat(firstQuery.get()).contains("language=pl", "time_range=day", "categories=shopping", "pageno=2");
        assertThat(secondQuery.get()).contains("q=RTX%203060", "format=json");
        assertThat(secondQuery.get()).doesNotContain("language=", "time_range=", "categories=", "pageno=");
        assertThat(response.results()).extracting(WebSearchResult::title).containsExactly("Fallback result");
    }

    private SearxngWebSearchClient client(WebSearchProperties properties) {
        return new SearxngWebSearchClient(properties, new ObjectMapper());
    }

    private WebSearchProperties defaultProperties() {
        return new WebSearchProperties(true, baseUrl(), 5, 10, 320, 8000,
                Duration.ofSeconds(1), Duration.ofSeconds(1));
    }

    private void startServer(int status, String body) throws IOException {
        startServer(exchange -> write(exchange, status, body));
    }

    private void startServer(ExchangeHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search", handler::handle);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void write(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
