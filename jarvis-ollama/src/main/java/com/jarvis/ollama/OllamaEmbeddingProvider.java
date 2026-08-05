package com.jarvis.ollama;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.common.embedding.EmbeddingProvider;
import com.jarvis.common.embedding.EmbeddingVector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Ollama-backed embedding provider.
 */
@Service
public class OllamaEmbeddingProvider implements EmbeddingProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(OllamaEmbeddingProvider.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final OllamaProperties properties;
    private final String model;

    /**
     * Creates the Ollama embedding provider.
     *
     * @param httpClient HTTP client
     * @param objectMapper JSON mapper
     * @param properties Ollama properties
     * @param model configured embedding model
     */
    public OllamaEmbeddingProvider(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            OllamaProperties properties,
            @Value("${jarvis.memory.embedding.model:nomic-embed-text}") String model
    ) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.model = model;
    }

    @Override
    public String provider() {
        return "ollama";
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public EmbeddingVector embed(String text) {
        long started = System.nanoTime();
        String endpoint = normalizeBaseUrl(properties.baseUrl()) + "/api/embeddings";
        try {
            OllamaEmbeddingRequest request = new OllamaEmbeddingRequest(model, text == null ? "" : text);
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofMinutes(2))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request)))
                    .build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new OllamaException("Ollama embedding request failed with status " + response.statusCode());
            }
            OllamaEmbeddingResponse embeddingResponse = objectMapper.readValue(response.body(), OllamaEmbeddingResponse.class);
            List<Double> embedding = embeddingResponse.embedding() == null ? List.of() : embeddingResponse.embedding();
            long durationMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            LOGGER.info("""
                    [JARVIS]
                    EMBEDDING GENERATED

                    Model:
                    {}

                    Dimensions:
                    {}

                    Generation time:
                    {} ms
                    """, model, embedding.size(), durationMs);
            return new EmbeddingVector(model, embedding, durationMs);
        } catch (JsonProcessingException exception) {
            throw new OllamaException("Failed to process Ollama embedding JSON", exception);
        } catch (IOException exception) {
            throw new OllamaException("Failed to communicate with Ollama embedding API", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new OllamaException("Ollama embedding request was interrupted", exception);
        }
    }

    private String normalizeBaseUrl(String baseUrl) {
        if (baseUrl.endsWith("/")) {
            return baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl;
    }

    private record OllamaEmbeddingRequest(String model, String prompt) {
    }

    private record OllamaEmbeddingResponse(List<Double> embedding) {
    }
}
