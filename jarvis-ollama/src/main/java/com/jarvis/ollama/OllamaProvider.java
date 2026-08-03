package com.jarvis.ollama;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.common.ai.AIProvider;
import com.jarvis.common.ai.Brain;
import com.jarvis.common.dto.ChatResponse;
import com.jarvis.common.event.ChatEventSink;
import com.jarvis.common.event.ChatEventType;
import com.jarvis.common.event.ErrorEvent;
import com.jarvis.common.event.GenerationFinishedEvent;
import com.jarvis.common.event.StatusChangedEvent;
import com.jarvis.common.event.ThinkingEvent;
import com.jarvis.common.event.TokenEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

/**
 * Ollama implementation of the provider-independent AI provider contract.
 */
@Service
public class OllamaProvider implements AIProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(OllamaProvider.class);

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
    public OllamaProvider(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            OllamaProperties properties
    ) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /**
     * Returns the provider identifier handled by this implementation.
     *
     * @return provider identifier
     */
    @Override
    public String provider() {
        return "ollama";
    }

    /**
     * Sends a streaming generation request to Ollama and returns the full response for REST compatibility.
     *
     * @param brain selected logical brain
     * @param prompt prepared prompt
     * @return generated response text
     */
    @Override
    public ChatResponse chat(Brain brain, String prompt) {
        StringBuilder responseBuilder = new StringBuilder();
        stream("", brain, prompt, event -> {
            if (event instanceof TokenEvent tokenEvent) {
                responseBuilder.append(tokenEvent.text());
            }
        });
        return new ChatResponse(responseBuilder.toString());
    }

    /**
     * Streams a prepared prompt through Ollama.
     *
     * @param conversationId conversation identifier
     * @param brain selected logical brain
     * @param prompt prepared prompt
     * @param eventSink event sink
     */
    @Override
    public void stream(String conversationId, Brain brain, String prompt, ChatEventSink eventSink) {
        Instant startedAt = Instant.now();
        try {
            LOGGER.info("[JARVIS] Model: {}", brain.model());
            eventSink.publish(StatusChangedEvent.create(ChatEventType.MODEL_LOADING, conversationId, "MODEL_LOADING"));

            OllamaGenerateRequest requestBody = new OllamaGenerateRequest(brain.model(), prompt, true);
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(normalizeBaseUrl(properties.baseUrl()) + "/api/generate"))
                    .timeout(Duration.ofMinutes(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                    .build();

            Instant requestStartedAt = Instant.now();
            HttpResponse<InputStream> httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            long ollamaRequestLatencyMs = Duration.between(requestStartedAt, Instant.now()).toMillis();
            LOGGER.info("[JARVIS] Ollama request latency: {} ms", ollamaRequestLatencyMs);

            if (httpResponse.statusCode() < 200 || httpResponse.statusCode() >= 300) {
                LOGGER.error("[JARVIS] Ollama HTTP error. Status: {}", httpResponse.statusCode());
                throw new OllamaException("Ollama request failed with status " + httpResponse.statusCode());
            }

            eventSink.publish(ThinkingEvent.create(conversationId));
            streamResponse(conversationId, brain, httpResponse.body(), startedAt, eventSink);
        } catch (JsonProcessingException exception) {
            LOGGER.error("[JARVIS] Ollama JSON error", exception);
            eventSink.publish(ErrorEvent.create(conversationId, "AI provider response could not be processed"));
            throw new OllamaException("Failed to serialize Ollama request", exception);
        } catch (IOException exception) {
            LOGGER.error("[JARVIS] Ollama is unreachable at {}", properties.baseUrl(), exception);
            eventSink.publish(ErrorEvent.create(conversationId, "AI provider disconnected"));
            throw new OllamaException("Failed to communicate with Ollama", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            LOGGER.error("[JARVIS] Ollama request interrupted", exception);
            eventSink.publish(ErrorEvent.create(conversationId, "AI provider request was interrupted"));
            throw new OllamaException("Ollama request was interrupted", exception);
        }
    }

    private void streamResponse(
            String conversationId,
            Brain brain,
            InputStream inputStream,
            Instant startedAt,
            ChatEventSink eventSink
    ) throws IOException {
        boolean generationStarted = false;
        Instant firstTokenAt = null;
        OllamaGenerateResponse finalResponse = null;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }

                OllamaGenerateResponse response = objectMapper.readValue(line, OllamaGenerateResponse.class);
                if (Boolean.TRUE.equals(response.done())) {
                    finalResponse = response;
                    break;
                }

                String token = response.response();
                if (token == null || token.isEmpty()) {
                    continue;
                }

                if (!generationStarted) {
                    generationStarted = true;
                    firstTokenAt = Instant.now();
                    eventSink.publish(StatusChangedEvent.create(ChatEventType.GENERATING, conversationId, "GENERATING"));
                    LOGGER.info(
                            "[JARVIS] Time to first token: {} ms",
                            Duration.between(startedAt, firstTokenAt).toMillis()
                    );
                }
                eventSink.publish(TokenEvent.create(conversationId, token));
            }
        }

        long generationTimeMs = Duration.between(startedAt, Instant.now()).toMillis();
        Integer completionTokens = finalResponse == null ? null : finalResponse.evalCount();
        Integer promptTokens = finalResponse == null ? null : finalResponse.promptEvalCount();
        Double tokensPerSecond = tokensPerSecond(completionTokens, generationTimeMs);

        LOGGER.info(
                "[JARVIS] Generation time: {} ms, tokens: {}, tokens per second: {}",
                generationTimeMs,
                tokenSummary(finalResponse),
                tokensPerSecond == null ? "unavailable" : tokensPerSecond
        );
        eventSink.publish(GenerationFinishedEvent.create(
                conversationId,
                generationTimeMs,
                brain.type(),
                brain.model(),
                promptTokens,
                completionTokens,
                tokensPerSecond
        ));
        eventSink.publish(StatusChangedEvent.create(ChatEventType.IDLE, conversationId, "IDLE"));
    }

    private String normalizeBaseUrl(String baseUrl) {
        if (baseUrl.endsWith("/")) {
            return baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl;
    }

    private String tokenSummary(OllamaGenerateResponse response) {
        if (response == null || response.evalCount() == null && response.promptEvalCount() == null) {
            return "unavailable";
        }
        return "prompt=%s, completion=%s".formatted(response.promptEvalCount(), response.evalCount());
    }

    private Double tokensPerSecond(Integer completionTokens, long generationTimeMs) {
        if (completionTokens == null || generationTimeMs <= 0) {
            return null;
        }
        return completionTokens / (generationTimeMs / 1000.0d);
    }
}
