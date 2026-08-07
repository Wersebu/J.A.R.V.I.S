package com.jarvis.ollama;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.common.ai.AIProvider;
import com.jarvis.common.ai.AIJobType;
import com.jarvis.common.ai.Brain;
import com.jarvis.common.dto.ChatResponse;
import com.jarvis.common.diagnostics.InferenceDiagnostics;
import com.jarvis.common.diagnostics.InferenceDiagnosticsContext;
import com.jarvis.common.diagnostics.OllamaBottleneckType;
import com.jarvis.common.diagnostics.OllamaInferenceMetrics;
import com.jarvis.common.event.ChatEventSink;
import com.jarvis.common.event.CognitiveEventBus;
import com.jarvis.common.event.CognitiveEventType;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Ollama implementation of the provider-independent AI provider contract.
 */
@Service
public class OllamaProvider implements AIProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(OllamaProvider.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final OllamaProperties properties;
    private final CognitiveEventBus cognitiveEventBus;
    private final OllamaRequestCoordinator requestCoordinator;

    /**
     * Creates the Ollama HTTP service.
     *
     * @param httpClient HTTP client
     * @param objectMapper JSON mapper
     * @param properties Ollama configuration
     * @param cognitiveEventBus cognitive event bus
     * @param requestCoordinator Ollama request coordinator
     */
    public OllamaProvider(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            OllamaProperties properties,
            CognitiveEventBus cognitiveEventBus,
            OllamaRequestCoordinator requestCoordinator
    ) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.cognitiveEventBus = cognitiveEventBus;
        this.requestCoordinator = requestCoordinator;
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
        return chat(brain, prompt, AIJobType.CHAT);
    }

    @Override
    public ChatResponse chat(Brain brain, String prompt, AIJobType jobType) {
        StringBuilder responseBuilder = new StringBuilder();
        stream("", brain, prompt, jobType, event -> {
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
        stream(conversationId, brain, prompt, AIJobType.CHAT, eventSink);
    }

    @Override
    public void stream(String conversationId, Brain brain, String prompt, AIJobType jobType, ChatEventSink eventSink) {
        Instant startedAt = Instant.now();
        long startedNano = System.nanoTime();
        String requestId = diagnosticsRequestId();
        try {
            LOGGER.info("[JARVIS][requestId={}][OLLAMA] HTTP_CALL_PREPARING model={} reasoning={}",
                    requestId, brain.model(), brain.reasoningLevel());
            eventSink.publish(StatusChangedEvent.create(ChatEventType.MODEL_LOADING, conversationId, "MODEL_LOADING"));
            String endpoint = normalizeBaseUrl(properties.baseUrl()) + "/api/generate";
            publishCognitive(jobType, CognitiveEventType.MODEL_REQUEST_STARTED, "REQUESTING", "Model request started", "model:" + brain.model(), Map.of(
                    "model", brain.model(),
                    "endpoint", endpoint,
                    "provider", provider(),
                    "reasoningLevel", brain.reasoningLevel().name()
            ));

            try (OllamaRequestCoordinator.Permit ignored = requestCoordinator.acquire(jobType, requestId)) {
                long httpPreparationStarted = System.nanoTime();
                OllamaGenerateRequest requestBody = new OllamaGenerateRequest(
                        brain.model(),
                        prompt,
                        true,
                        brain.reasoningLevel().name().toLowerCase(java.util.Locale.ROOT),
                        properties.keepAlive()
                );
                HttpRequest httpRequest = HttpRequest.newBuilder()
                        .uri(URI.create(endpoint))
                        .timeout(Duration.ofMinutes(5))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                        .build();
                InferenceDiagnostics diagnostics = InferenceDiagnosticsContext.current();
                if (diagnostics != null) {
                    diagnostics.setHttpClientPreparationMs(nanosToMillis(System.nanoTime() - httpPreparationStarted));
                    diagnostics.setModel(brain.model());
                    diagnostics.setReasoningLevel(brain.reasoningLevel().name());
                }

                long requestStartedNano = System.nanoTime();
                LOGGER.info("[JARVIS][requestId={}][OLLAMA] HTTP_CALL_STARTED model={} reasoning={}",
                        requestId, brain.model(), brain.reasoningLevel());
                publishCognitive(jobType, CognitiveEventType.EXECUTION_TRACE, "STARTED", "Ollama HTTP request started",
                        "model:" + brain.model(), Map.of(
                                "stage", "OLLAMA_RESPONSE_HEADERS_WAIT",
                                "phase", "STARTED",
                                "durationMs", 0,
                                "model", brain.model(),
                                "endpoint", endpoint,
                                "severity", "GREEN"
                        ));
                HttpResponse<InputStream> httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
                long ollamaRequestLatencyMs = nanosToMillis(System.nanoTime() - requestStartedNano);
                if (diagnostics != null) {
                    diagnostics.setResponseHeadersReceivedMs(ollamaRequestLatencyMs);
                }
                LOGGER.info("[JARVIS][requestId={}][OLLAMA] RESPONSE_HEADERS_RECEIVED elapsedMs={}",
                        requestId, ollamaRequestLatencyMs);
                publishCognitive(jobType, CognitiveEventType.EXECUTION_TRACE, "FINISHED", "Ollama response headers received",
                        "model:" + brain.model(), Map.of(
                                "stage", "OLLAMA_RESPONSE_HEADERS_WAIT",
                                "phase", "FINISHED",
                                "durationMs", ollamaRequestLatencyMs,
                                "model", brain.model(),
                                "httpStatus", httpResponse.statusCode(),
                                "severity", severity(ollamaRequestLatencyMs)
                        ));
                if (ollamaRequestLatencyMs > 500L) {
                    publishCognitive(jobType, CognitiveEventType.EXECUTION_BOTTLENECK,
                            ollamaRequestLatencyMs > 5_000L ? "HIGH" : "MEDIUM",
                            "Potential Bottleneck", "model:" + brain.model(), Map.of(
                                    "stage", "OLLAMA_RESPONSE_HEADERS_WAIT",
                                    "durationMs", ollamaRequestLatencyMs,
                                    "type", "TRANSPORT",
                                    "reason", "Waiting for Ollama response headers; this may include model scheduling, loading, prompt evaluation and transport",
                                    "severity", ollamaRequestLatencyMs > 5_000L ? "HIGH" : "MEDIUM"
                            ));
                }

                if (httpResponse.statusCode() < 200 || httpResponse.statusCode() >= 300) {
                    LOGGER.error("[JARVIS][requestId={}][OLLAMA] HTTP_ERROR status={}", requestId, httpResponse.statusCode());
                    throw new OllamaException("Ollama request failed with status " + httpResponse.statusCode());
                }

                eventSink.publish(ThinkingEvent.create(conversationId));
                publishCognitive(jobType, CognitiveEventType.WAITING_FIRST_TOKEN, "WAITING", "Waiting for first token", "model:" + brain.model(), Map.of(
                        "model", brain.model(),
                        "requestLatencyMs", ollamaRequestLatencyMs,
                        "reasoningLevel", brain.reasoningLevel().name()
                ));
                streamResponse(conversationId, brain, httpResponse.body(), startedAt, startedNano, ollamaRequestLatencyMs, jobType, eventSink);
            }
        } catch (JsonProcessingException exception) {
            LOGGER.error("[JARVIS] Ollama JSON error", exception);
            eventSink.publish(ErrorEvent.create(conversationId, "AI provider response could not be processed"));
            cognitiveEventBus.error("AI provider response could not be processed", Map.of(
                    "exception", exception.getClass().getSimpleName()
            ));
            throw new OllamaException("Failed to serialize Ollama request", exception);
        } catch (IOException exception) {
            LOGGER.error("[JARVIS] Ollama is unreachable at {}", properties.baseUrl(), exception);
            eventSink.publish(ErrorEvent.create(conversationId, "AI provider disconnected"));
            cognitiveEventBus.error("AI provider disconnected", Map.of(
                    "baseUrl", properties.baseUrl(),
                    "exception", exception.getClass().getSimpleName()
            ));
            throw new OllamaException("Failed to communicate with Ollama", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            LOGGER.error("[JARVIS] Ollama request interrupted", exception);
            eventSink.publish(ErrorEvent.create(conversationId, "AI provider request was interrupted"));
            cognitiveEventBus.error("AI provider request was interrupted", Map.of(
                    "exception", exception.getClass().getSimpleName()
            ));
            throw new OllamaException("Ollama request was interrupted", exception);
        }
    }

    private void streamResponse(
            String conversationId,
            Brain brain,
            InputStream inputStream,
            Instant startedAt,
            long startedNano,
            long requestStartToHeadersMs,
            AIJobType jobType,
            ChatEventSink eventSink
    ) throws IOException {
        boolean thinkingStarted = false;
        boolean thinkingFinished = false;
        boolean answerStarted = false;
        long firstThinkingNano = 0L;
        long lastThinkingNano = 0L;
        long firstAnswerNano = 0L;
        long firstThinkingTokenMs = 0L;
        long firstAnswerTokenMs = 0L;
        OllamaGenerateResponse finalResponse = null;
        int thinkingChunks = 0;
        int answerChunks = 0;
        int thinkingCharacters = 0;
        int answerCharacters = 0;

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

                String thinking = response.thinking();
                if (thinking != null && !thinking.isEmpty()) {
                    if (!thinkingStarted) {
                        thinkingStarted = true;
                        firstThinkingNano = System.nanoTime();
                        long elapsedMs = nanosToMillis(firstThinkingNano - startedNano);
                        firstThinkingTokenMs = elapsedMs;
                        diagnostics(d -> d.setFirstThinkingTokenMs(elapsedMs));
                        publishCognitive(jobType, CognitiveEventType.FIRST_TOKEN_RECEIVED, "RECEIVED", "First model output received", "model:" + brain.model(), Map.of(
                                "latencyMs", elapsedMs,
                                "model", brain.model(),
                                "channel", "thinking"
                        ));
                        publishCognitive(jobType, CognitiveEventType.THINKING_STARTED, "THINKING", "Thinking started", "model:" + brain.model(), Map.of(
                                "model", brain.model(),
                                "reasoningLevel", brain.reasoningLevel().name()
                        ));
                        LOGGER.info("[JARVIS][requestId={}][OLLAMA] FIRST_THINKING_CHUNK elapsedMs={}", diagnosticsRequestId(), elapsedMs);
                    }
                    lastThinkingNano = System.nanoTime();
                    thinkingChunks++;
                    thinkingCharacters += thinking.length();
                    publishCognitive(jobType, CognitiveEventType.THINKING_TOKEN, "THINKING", thinking, "model:" + brain.model(), Map.of(
                            "text", thinking,
                            "index", thinkingChunks
                    ));
                }

                String token = response.response();
                if (token != null && !token.isEmpty()) {
                    if (thinkingStarted && !thinkingFinished) {
                        thinkingFinished = true;
                        long durationMs = nanosToMillis((lastThinkingNano == 0L ? System.nanoTime() : lastThinkingNano) - firstThinkingNano);
                        long finalThinkingChunks = thinkingChunks;
                        diagnostics(d -> {
                            d.setThinkingDurationMs(durationMs);
                            d.setThinkingTokensOrChunks(finalThinkingChunks);
                        });
                        publishCognitive(jobType, CognitiveEventType.THINKING_FINISHED, "FINISHED", "Thinking finished", "model:" + brain.model(), Map.of(
                                "durationMs", durationMs,
                                "chunks", thinkingChunks,
                                "characters", thinkingCharacters
                        ));
                    }
                    if (!answerStarted) {
                        answerStarted = true;
                        firstAnswerNano = System.nanoTime();
                        long firstTokenLatencyMs = nanosToMillis(firstAnswerNano - startedNano);
                        firstAnswerTokenMs = firstTokenLatencyMs;
                        diagnostics(d -> d.setFirstAnswerTokenMs(firstTokenLatencyMs));
                        LOGGER.info("[JARVIS][requestId={}][OLLAMA] FIRST_ANSWER_TOKEN elapsedMs={}",
                                diagnosticsRequestId(), firstTokenLatencyMs);
                        publishCognitive(jobType, CognitiveEventType.ANSWER_STARTED, "ANSWERING", "Answer started", "model:" + brain.model(), Map.of(
                                "model", brain.model(),
                                "timeToFirstAnswerTokenMs", firstTokenLatencyMs
                        ));
                        eventSink.publish(StatusChangedEvent.create(ChatEventType.GENERATING, conversationId, "GENERATING"));
                        publishCognitive(jobType, CognitiveEventType.FIRST_TOKEN_RECEIVED, "RECEIVED", "First token received", "model:" + brain.model(), Map.of(
                                "latencyMs", firstTokenLatencyMs,
                                "model", brain.model()
                        ));
                        publishCognitive(jobType, CognitiveEventType.STREAMING_STARTED, "STREAMING", "Streaming started", "model:" + brain.model(), Map.of(
                                "model", brain.model()
                        ));
                        LOGGER.info("[JARVIS][requestId={}][OLLAMA] TIME_TO_FIRST_TOKEN elapsedMs={}",
                                diagnosticsRequestId(), firstTokenLatencyMs);
                    }
                    answerChunks++;
                    answerCharacters += token.length();
                    publishCognitive(jobType, CognitiveEventType.ANSWER_TOKEN, "TOKEN", token, "model:" + brain.model(), Map.of(
                            "text", token,
                            "index", answerChunks
                    ));
                    publishCognitive(jobType, CognitiveEventType.TOKEN, "TOKEN", token, "model:" + brain.model(), Map.of(
                            "text", token,
                            "index", answerChunks
                    ));
                    eventSink.publish(TokenEvent.create(conversationId, token));
                }
            }
        }

        if (thinkingStarted && !thinkingFinished) {
            long durationMs = nanosToMillis((lastThinkingNano == 0L ? System.nanoTime() : lastThinkingNano) - firstThinkingNano);
            long finalThinkingChunks = thinkingChunks;
            diagnostics(d -> {
                d.setThinkingDurationMs(durationMs);
                d.setThinkingTokensOrChunks(finalThinkingChunks);
            });
            publishCognitive(jobType, CognitiveEventType.THINKING_FINISHED, "FINISHED", "Thinking finished", "model:" + brain.model(), Map.of(
                    "durationMs", durationMs,
                    "chunks", thinkingChunks,
                    "characters", thinkingCharacters
            ));
        }
        if (answerStarted) {
            long answerDurationMs = nanosToMillis(System.nanoTime() - firstAnswerNano);
            diagnostics(d -> d.setAnswerStreamingDurationMs(answerDurationMs));
            publishCognitive(jobType, CognitiveEventType.ANSWER_FINISHED, "FINISHED", "Answer finished", "model:" + brain.model(), Map.of(
                    "durationMs", answerDurationMs,
                    "characters", answerCharacters,
                    "tokens", finalResponse == null || finalResponse.evalCount() == null ? answerChunks : finalResponse.evalCount()
            ));
        }

        long generationTimeMs = Duration.between(startedAt, Instant.now()).toMillis();
        diagnostics(d -> d.setTotalModelRequestMs(generationTimeMs));
        Integer completionTokens = finalResponse == null ? null : finalResponse.evalCount();
        Integer promptTokens = finalResponse == null ? null : finalResponse.promptEvalCount();
        Double tokensPerSecond = tokensPerSecond(completionTokens, generationTimeMs);
        OllamaInferenceMetrics metrics = buildMetrics(
                brain,
                finalResponse,
                requestStartToHeadersMs,
                firstThinkingTokenMs,
                firstAnswerTokenMs
        );
        publishMetrics(jobType, brain, metrics, generationTimeMs);

        LOGGER.info(
                "[JARVIS] Generation time: {} ms, tokens: {}, tokens per second: {}",
                generationTimeMs,
                tokenSummary(finalResponse),
                tokensPerSecond == null ? "unavailable" : tokensPerSecond
        );
        publishCognitive(jobType, CognitiveEventType.STREAMING_FINISHED, "FINISHED", "Streaming finished", "model:" + brain.model(), Map.of(
                "generationTimeMs", generationTimeMs,
                "promptTokens", promptTokens == null ? 0 : promptTokens,
                "completionTokens", completionTokens == null ? answerChunks : completionTokens,
                "tokensStreamed", answerChunks,
                "tokensPerSecond", tokensPerSecond == null ? 0.0d : tokensPerSecond
        ));
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

    private void publishCognitive(
            AIJobType jobType,
            CognitiveEventType event,
            String status,
            String message,
            String nodeId,
            Map<String, Object> metadata
    ) {
        if (jobType == AIJobType.CHAT || jobType == AIJobType.MAIN_MODEL) {
            if (jobType == AIJobType.MAIN_MODEL
                    && (event == CognitiveEventType.ANSWER_STARTED
                    || event == CognitiveEventType.ANSWER_TOKEN
                    || event == CognitiveEventType.TOKEN
                    || event == CognitiveEventType.ANSWER_FINISHED
                    || event == CognitiveEventType.STREAMING_STARTED
                    || event == CognitiveEventType.STREAMING_FINISHED)) {
                return;
            }
            cognitiveEventBus.publish(event, status, message, nodeId, metadata);
        }
    }

    private String normalizeBaseUrl(String baseUrl) {
        if (baseUrl.endsWith("/")) {
            return baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl;
    }

    private long nanosToMillis(long nanos) {
        return TimeUnit.NANOSECONDS.toMillis(nanos);
    }

    private String severity(long durationMs) {
        if (durationMs < 20L) {
            return "GREEN";
        }
        if (durationMs < 100L) {
            return "YELLOW";
        }
        if (durationMs < 500L) {
            return "ORANGE";
        }
        return "RED";
    }

    private void diagnostics(java.util.function.Consumer<InferenceDiagnostics> consumer) {
        InferenceDiagnostics diagnostics = InferenceDiagnosticsContext.current();
        if (diagnostics != null) {
            consumer.accept(diagnostics);
        }
    }

    private String diagnosticsRequestId() {
        InferenceDiagnostics diagnostics = InferenceDiagnosticsContext.current();
        return diagnostics == null ? "background" : diagnostics.getRequestId().toString();
    }

    private String promptPreview(String prompt) {
        String compact = prompt == null ? "" : prompt.replaceAll("\\s+", " ").strip();
        if (compact.length() <= 500) {
            return compact;
        }
        return compact.substring(0, 500) + "...";
    }

    private String tokenSummary(OllamaGenerateResponse response) {
        if (response == null || response.evalCount() == null && response.promptEvalCount() == null) {
            return "unavailable";
        }
        return "prompt=%s, completion=%s, loadMs=%s, promptEvalMs=%s, evalMs=%s".formatted(
                response.promptEvalCount(),
                response.evalCount(),
                nsToMs(response.loadDuration()),
                nsToMs(response.promptEvalDuration()),
                nsToMs(response.evalDuration())
        );
    }

    private Double tokensPerSecond(Integer completionTokens, long generationTimeMs) {
        if (completionTokens == null || generationTimeMs <= 0) {
            return null;
        }
        return completionTokens / (generationTimeMs / 1000.0d);
    }

    private OllamaInferenceMetrics buildMetrics(
            Brain brain,
            OllamaGenerateResponse response,
            long requestStartToHeadersMs,
            long firstThinkingTokenMs,
            long firstAnswerTokenMs
    ) {
        long totalDurationMs = nsToMs(response == null ? null : response.totalDuration());
        long loadDurationMs = nsToMs(response == null ? null : response.loadDuration());
        long promptEvalCount = response == null || response.promptEvalCount() == null ? 0L : response.promptEvalCount();
        long promptEvalDurationMs = nsToMs(response == null ? null : response.promptEvalDuration());
        long evalCount = response == null || response.evalCount() == null ? 0L : response.evalCount();
        long evalDurationMs = nsToMs(response == null ? null : response.evalDuration());
        long queueWaitMs = diagnosticsValue(InferenceDiagnostics::getOllamaPermitQueueWaitMs);
        OllamaBottleneckType bottleneck = classifyBottleneck(
                totalDurationMs,
                loadDurationMs,
                promptEvalDurationMs,
                evalDurationMs,
                queueWaitMs,
                requestStartToHeadersMs
        );
        return new OllamaInferenceMetrics(
                brain.model(),
                totalDurationMs,
                loadDurationMs,
                promptEvalCount,
                promptEvalDurationMs,
                evalCount,
                evalDurationMs,
                tokensPerSecond(promptEvalCount, promptEvalDurationMs),
                tokensPerSecond(evalCount, evalDurationMs),
                requestStartToHeadersMs,
                firstThinkingTokenMs,
                firstAnswerTokenMs,
                response != null && response.loadDuration() != null && loadDurationMs < 1_000L,
                bottleneck
        );
    }

    private void publishMetrics(AIJobType jobType, Brain brain, OllamaInferenceMetrics metrics, long requestTotalMs) {
        InferenceDiagnostics activeDiagnostics = InferenceDiagnosticsContext.current();
        diagnostics(d -> {
            d.setOllamaMetrics(metrics);
            d.setActualPromptTokens(Math.toIntExact(Math.min(Integer.MAX_VALUE, metrics.promptEvalCount())));
            d.setModelWasAlreadyLoaded(metrics.modelLikelyWarm());
        });
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("model", metrics.model());
        metadata.put("reasoningLevel", brain.reasoningLevel().name());
        metadata.put("totalDurationMs", metrics.totalDurationMs());
        metadata.put("requestTotalMs", requestTotalMs);
        metadata.put("loadDurationMs", metrics.loadDurationMs());
        metadata.put("promptEvalCount", metrics.promptEvalCount());
        metadata.put("promptEvalDurationMs", metrics.promptEvalDurationMs());
        metadata.put("evalCount", metrics.evalCount());
        metadata.put("evalDurationMs", metrics.evalDurationMs());
        metadata.put("promptTokensPerSecond", metrics.promptTokensPerSecond());
        metadata.put("generationTokensPerSecond", metrics.generationTokensPerSecond());
        metadata.put("requestStartToHeadersMs", metrics.requestStartToHeadersMs());
        metadata.put("firstThinkingTokenMs", metrics.firstThinkingTokenMs());
        metadata.put("firstAnswerTokenMs", metrics.firstAnswerTokenMs());
        metadata.put("modelLikelyWarm", metrics.modelLikelyWarm());
        metadata.put("bottleneck", metrics.bottleneck().name());
        if (activeDiagnostics != null) {
            appendPromptMetrics(metadata, activeDiagnostics, metrics);
        }
        publishCognitive(jobType, CognitiveEventType.OLLAMA_METRICS, "MEASURED", "Native Ollama metrics captured",
                "model:" + brain.model(), metadata);
        publishNativeTrace(jobType, brain, "OLLAMA_MODEL_LOAD", metrics.loadDurationMs());
        publishNativeTrace(jobType, brain, "OLLAMA_PROMPT_EVAL", metrics.promptEvalDurationMs());
        publishNativeTrace(jobType, brain, "OLLAMA_GENERATION", metrics.evalDurationMs());
        publishClassifiedBottleneck(jobType, brain, metrics, requestTotalMs);
        LOGGER.info(
                "[OLLAMA_METRICS] requestId={} model={} loadMs={} promptEvalTokens={} promptEvalMs={} promptTokPerSec={} evalTokens={} evalMs={} generationTokPerSec={} totalMs={}",
                diagnosticsRequestId(),
                metrics.model(),
                metrics.loadDurationMs(),
                metrics.promptEvalCount(),
                metrics.promptEvalDurationMs(),
                "%.2f".formatted(metrics.promptTokensPerSecond()),
                metrics.evalCount(),
                metrics.evalDurationMs(),
                "%.2f".formatted(metrics.generationTokensPerSecond()),
                metrics.totalDurationMs()
        );
        LOGGER.info("[MODEL_BOTTLENECK] requestId={} type={} shareOfTotal={}%",
                diagnosticsRequestId(), metrics.bottleneck(), "%.1f".formatted(bottleneckShare(metrics) * 100.0d));
    }

    private void appendPromptMetrics(Map<String, Object> metadata, InferenceDiagnostics diagnostics, OllamaInferenceMetrics metrics) {
        metadata.put("systemPromptChars", value(diagnostics.getSystemPromptChars()));
        metadata.put("conversationContextChars", value(diagnostics.getConversationContextChars()));
        metadata.put("knowledgeContextChars", value(diagnostics.getKnowledgeContextChars()));
        metadata.put("toolCapabilityChars", value(diagnostics.getToolCapabilityChars()));
        metadata.put("currentUserMessageChars", value(diagnostics.getCurrentUserMessageChars()));
        metadata.put("totalPromptChars", value(diagnostics.getTotalPromptChars()));
        metadata.put("estimatedPromptTokens", value(diagnostics.getEstimatedPromptTokens()));
        metadata.put("actualPromptTokens", metrics.promptEvalCount());
        LOGGER.info(
                "[PROMPT_METRICS] requestId={} systemChars={} conversationChars={} knowledgeChars={} toolChars={} userChars={} totalChars={} estimatedPromptTokens={} actualPromptTokens={}",
                diagnosticsRequestId(),
                value(diagnostics.getSystemPromptChars()),
                value(diagnostics.getConversationContextChars()),
                value(diagnostics.getKnowledgeContextChars()),
                value(diagnostics.getToolCapabilityChars()),
                value(diagnostics.getCurrentUserMessageChars()),
                value(diagnostics.getTotalPromptChars()),
                value(diagnostics.getEstimatedPromptTokens()),
                metrics.promptEvalCount()
        );
    }

    private int value(Integer value) {
        return value == null ? 0 : value;
    }

    private void publishNativeTrace(AIJobType jobType, Brain brain, String stage, long durationMs) {
        if (durationMs <= 0L) {
            return;
        }
        publishCognitive(jobType, CognitiveEventType.EXECUTION_TRACE, "FINISHED", stage + " measured by Ollama",
                "model:" + brain.model(), Map.of(
                        "stage", stage,
                        "phase", "FINISHED",
                        "durationMs", durationMs,
                        "model", brain.model(),
                        "nativeMetric", true,
                        "severity", severity(durationMs)
                ));
    }

    private void publishClassifiedBottleneck(AIJobType jobType, Brain brain, OllamaInferenceMetrics metrics, long requestTotalMs) {
        if (metrics.bottleneck() == OllamaBottleneckType.UNKNOWN) {
            return;
        }
        String stage = switch (metrics.bottleneck()) {
            case MODEL_LOAD -> "OLLAMA_MODEL_LOAD";
            case PROMPT_EVALUATION -> "OLLAMA_PROMPT_EVAL";
            case QUEUE_WAIT -> "OLLAMA_QUEUE_WAIT";
            case GENERATION -> "OLLAMA_GENERATION";
            case TRANSPORT -> "OLLAMA_RESPONSE_HEADERS_WAIT";
            case UNKNOWN -> "OLLAMA_UNKNOWN";
        };
        long durationMs = switch (metrics.bottleneck()) {
            case MODEL_LOAD -> metrics.loadDurationMs();
            case PROMPT_EVALUATION -> metrics.promptEvalDurationMs();
            case GENERATION -> metrics.evalDurationMs();
            case TRANSPORT -> metrics.requestStartToHeadersMs();
            case QUEUE_WAIT -> diagnosticsValue(InferenceDiagnostics::getOllamaPermitQueueWaitMs);
            case UNKNOWN -> 0L;
        };
        if (durationMs <= 500L) {
            return;
        }
        publishCognitive(jobType, CognitiveEventType.EXECUTION_BOTTLENECK,
                durationMs > 5_000L ? "HIGH" : "MEDIUM",
                "Potential Bottleneck", "model:" + brain.model(), Map.of(
                        "stage", stage,
                        "type", metrics.bottleneck().name(),
                        "durationMs", durationMs,
                        "shareOfTotal", bottleneckShare(metrics),
                        "requestTotalMs", requestTotalMs,
                        "reason", reason(metrics.bottleneck()),
                        "severity", durationMs > 5_000L ? "HIGH" : "MEDIUM"
                ));
    }

    private OllamaBottleneckType classifyBottleneck(
            long totalDurationMs,
            long loadDurationMs,
            long promptEvalDurationMs,
            long evalDurationMs,
            long queueWaitMs,
            long headersWaitMs
    ) {
        long modelTime = totalDurationMs > 0L ? totalDurationMs : Math.max(headersWaitMs, loadDurationMs + promptEvalDurationMs + evalDurationMs);
        long requestTime = Math.max(modelTime, headersWaitMs + evalDurationMs);
        if (requestTime > 0L && queueWaitMs / (double) requestTime > 0.40d) {
            return OllamaBottleneckType.QUEUE_WAIT;
        }
        if (modelTime <= 0L) {
            return headersWaitMs > 500L ? OllamaBottleneckType.TRANSPORT : OllamaBottleneckType.UNKNOWN;
        }
        if (loadDurationMs / (double) modelTime > 0.40d) {
            return OllamaBottleneckType.MODEL_LOAD;
        }
        if (promptEvalDurationMs / (double) modelTime > 0.40d) {
            return OllamaBottleneckType.PROMPT_EVALUATION;
        }
        if (evalDurationMs / (double) modelTime > 0.50d) {
            return OllamaBottleneckType.GENERATION;
        }
        return headersWaitMs > 500L && totalDurationMs <= 0L ? OllamaBottleneckType.TRANSPORT : OllamaBottleneckType.UNKNOWN;
    }

    private String reason(OllamaBottleneckType type) {
        return switch (type) {
            case MODEL_LOAD -> "Ollama spent most measured model time loading the model";
            case PROMPT_EVALUATION -> "Ollama spent most measured model time evaluating the prompt";
            case QUEUE_WAIT -> "Request waited behind another Ollama job";
            case GENERATION -> "Ollama spent most measured model time generating tokens";
            case TRANSPORT -> "Client waited for response headers; native model breakdown was insufficient";
            case UNKNOWN -> "Metrics do not support a clear conclusion";
        };
    }

    private double bottleneckShare(OllamaInferenceMetrics metrics) {
        long total = metrics.totalDurationMs() <= 0L ? 1L : metrics.totalDurationMs();
        return switch (metrics.bottleneck()) {
            case MODEL_LOAD -> metrics.loadDurationMs() / (double) total;
            case PROMPT_EVALUATION -> metrics.promptEvalDurationMs() / (double) total;
            case GENERATION -> metrics.evalDurationMs() / (double) total;
            case TRANSPORT -> metrics.requestStartToHeadersMs() / (double) Math.max(1L, metrics.requestStartToHeadersMs() + metrics.evalDurationMs());
            case QUEUE_WAIT -> diagnosticsValue(InferenceDiagnostics::getOllamaPermitQueueWaitMs)
                    / (double) Math.max(1L, metrics.requestStartToHeadersMs() + metrics.evalDurationMs());
            case UNKNOWN -> 0.0d;
        };
    }

    private long diagnosticsValue(java.util.function.Function<InferenceDiagnostics, Long> reader) {
        InferenceDiagnostics diagnostics = InferenceDiagnosticsContext.current();
        Long value = diagnostics == null ? null : reader.apply(diagnostics);
        return value == null ? 0L : value;
    }

    private long nsToMs(Long nanos) {
        return nanos == null ? 0L : nanosToMillis(nanos);
    }

    private double tokensPerSecond(long tokens, long durationMs) {
        if (tokens <= 0L || durationMs <= 0L) {
            return 0.0d;
        }
        return tokens / (durationMs / 1000.0d);
    }
}
