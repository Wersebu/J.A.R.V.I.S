package com.jarvis.ollama;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.common.ai.AIProvider;
import com.jarvis.common.dto.ChatRequest;
import com.jarvis.common.dto.ChatResponse;
import com.jarvis.common.prompt.PromptBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

/**
 * Ollama implementation of the provider-independent AI provider contract.
 */
@Service
@ConditionalOnProperty(name = "jarvis.ai.provider", havingValue = "ollama", matchIfMissing = true)
public class OllamaProvider implements AIProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(OllamaProvider.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final OllamaProperties properties;
    private final PromptBuilder promptBuilder;

    /**
     * Creates the Ollama HTTP service.
     *
     * @param httpClient HTTP client
     * @param objectMapper JSON mapper
     * @param properties Ollama configuration
     * @param promptBuilder provider-independent prompt builder
     */
    public OllamaProvider(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            OllamaProperties properties,
            PromptBuilder promptBuilder
    ) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.promptBuilder = promptBuilder;
    }

    /**
     * Sends a non-streaming generation request to Ollama.
     *
     * @param request provider chat request containing the composed prompt
     * @return generated response text
     */
    @Override
    public ChatResponse chat(ChatRequest request) {
        Instant startedAt = Instant.now();
        try {
            LOGGER.info("[JARVIS] Model: {}", properties.model());

            String prompt = promptBuilder.buildPrompt(request);
            OllamaGenerateRequest requestBody = new OllamaGenerateRequest(properties.model(), prompt, false);
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(normalizeBaseUrl(properties.baseUrl()) + "/api/generate"))
                    .timeout(Duration.ofMinutes(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                    .build();

            HttpResponse<String> httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (httpResponse.statusCode() < 200 || httpResponse.statusCode() >= 300) {
                LOGGER.error("[JARVIS] Ollama HTTP error. Status: {}", httpResponse.statusCode());
                throw new OllamaException("Ollama request failed with status " + httpResponse.statusCode());
            }
            OllamaGenerateResponse ollamaResponse = objectMapper.readValue(httpResponse.body(), OllamaGenerateResponse.class);
            LOGGER.info(
                    "[JARVIS] Generation time: {} ms, tokens: {}",
                    Duration.between(startedAt, Instant.now()).toMillis(),
                    tokenSummary(ollamaResponse)
            );
            return new ChatResponse(ollamaResponse.response());
        } catch (JsonProcessingException exception) {
            LOGGER.error("[JARVIS] Ollama JSON error", exception);
            throw new OllamaException("Failed to serialize Ollama request", exception);
        } catch (IOException exception) {
            LOGGER.error("[JARVIS] Ollama is unreachable at {}", properties.baseUrl(), exception);
            throw new OllamaException("Failed to communicate with Ollama", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            LOGGER.error("[JARVIS] Ollama request interrupted", exception);
            throw new OllamaException("Ollama request was interrupted", exception);
        }
    }

    private String normalizeBaseUrl(String baseUrl) {
        if (baseUrl.endsWith("/")) {
            return baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl;
    }

    private String tokenSummary(OllamaGenerateResponse response) {
        if (response.evalCount() == null && response.promptEvalCount() == null) {
            return "unavailable";
        }
        return "prompt=%s, completion=%s".formatted(response.promptEvalCount(), response.evalCount());
    }
}
