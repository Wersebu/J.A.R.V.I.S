package com.jarvis.ollama;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * HTTP implementation of the Ollama service.
 */
@Service
public class HttpOllamaService implements OllamaService {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final OllamaProperties properties;

    /**
     * Creates the Ollama HTTP service.
     *
     * @param httpClient HTTP client
     * @param objectMapper JSON mapper
     * @param properties Ollama configuration
     */
    public HttpOllamaService(HttpClient httpClient, ObjectMapper objectMapper, OllamaProperties properties) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /**
     * Sends a non-streaming generation request to Ollama.
     *
     * @param prompt prompt text
     * @return generated response text
     */
    @Override
    public String generate(String prompt) {
        try {
            OllamaGenerateRequest requestBody = new OllamaGenerateRequest(properties.model(), prompt, false);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(properties.baseUrl() + "/api/generate"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new OllamaException("Ollama request failed with status " + response.statusCode());
            }
            return objectMapper.readValue(response.body(), OllamaGenerateResponse.class).response();
        } catch (JsonProcessingException exception) {
            throw new OllamaException("Failed to serialize Ollama request", exception);
        } catch (IOException exception) {
            throw new OllamaException("Failed to communicate with Ollama", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new OllamaException("Ollama request was interrupted", exception);
        }
    }
}
