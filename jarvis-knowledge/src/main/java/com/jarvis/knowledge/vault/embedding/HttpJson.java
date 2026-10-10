package com.jarvis.knowledge.vault.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Minimal JSON-over-HTTP helper shared by the embedding backends.
 */
final class HttpJson {

    private final HttpClient client;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Duration timeout;
    private final String apiKey;

    HttpJson(Duration timeout, String apiKey) {
        this.timeout = timeout;
        this.apiKey = apiKey == null ? "" : apiKey;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    ObjectMapper mapper() {
        return mapper;
    }

    Response post(String url, Object body) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            if (!apiKey.isBlank()) {
                builder.header("Authorization", "Bearer " + apiKey);
            }
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode json = null;
            try {
                json = response.body() == null || response.body().isBlank() ? null : mapper.readTree(response.body());
            } catch (IOException ignored) {
                // non-JSON error bodies are reported as text
            }
            return new Response(response.statusCode(), response.body() == null ? "" : response.body(), json);
        } catch (IOException exception) {
            throw new EmbeddingBackend.EmbeddingException("Embedding provider unreachable at " + url + ": " + exception.getMessage(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new EmbeddingBackend.EmbeddingException("Embedding request interrupted", exception);
        }
    }

    static String trimSlash(String url) {
        String value = url == null ? "" : url.strip();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    record Response(int status, String body, JsonNode json) {
        boolean ok() {
            return status >= 200 && status < 300;
        }

        String shortBody() {
            String text = body.replaceAll("\\s+", " ").strip();
            return text.length() > 300 ? text.substring(0, 300) + "..." : text;
        }
    }
}
