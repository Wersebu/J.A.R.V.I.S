package com.jarvis.ollama;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.common.ai.AIProvider;
import com.jarvis.common.ai.AIJobType;
import com.jarvis.common.ai.Brain;
import com.jarvis.common.ai.ImageAttachment;
import com.jarvis.common.ai.ModelMessage;
import com.jarvis.common.ai.ModelResponse;
import com.jarvis.common.ai.ModelToolCall;
import com.jarvis.common.ai.ModelUsage;
import com.jarvis.common.ai.NativeToolDefinition;
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
import com.jarvis.common.event.ThinkingTokenEvent;
import com.jarvis.common.event.TokenEvent;
import com.jarvis.common.model.ActiveModelService;
import com.jarvis.common.model.ModelCapability;
import com.jarvis.common.model.QwenThinkingBudgetMode;
import com.jarvis.common.trace.AiTraceLogger;
import com.jarvis.common.trace.AiTraceTurnContext;
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
import java.util.List;
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
    private final ModelWarmupRegistry warmupRegistry;
    private final ContextBudgetService contextBudgetService;
    private final QwenThinkingBudgetProperties qwenThinkingBudgetProperties;
    private final ActiveModelService activeModelService;

    /**
     * Creates the Ollama HTTP service.
     *
     * @param httpClient HTTP client
     * @param objectMapper JSON mapper
     * @param properties Ollama configuration
     * @param cognitiveEventBus cognitive event bus
     * @param requestCoordinator Ollama request coordinator
     * @param qwenThinkingBudgetProperties Qwen thinking-budget configuration
     * @param activeModelService resolves the active model's declared capabilities
     */
    public OllamaProvider(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            OllamaProperties properties,
            CognitiveEventBus cognitiveEventBus,
            OllamaRequestCoordinator requestCoordinator,
            ModelWarmupRegistry warmupRegistry,
            ContextBudgetService contextBudgetService,
            QwenThinkingBudgetProperties qwenThinkingBudgetProperties,
            ActiveModelService activeModelService
    ) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.cognitiveEventBus = cognitiveEventBus;
        this.requestCoordinator = requestCoordinator;
        this.warmupRegistry = warmupRegistry;
        this.contextBudgetService = contextBudgetService;
        this.qwenThinkingBudgetProperties = qwenThinkingBudgetProperties;
        this.activeModelService = activeModelService;
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
        String response = responseBuilder.toString();
        return new ChatResponse(response);
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
        stream(conversationId, brain, prompt, jobType, List.of(), eventSink);
    }

    @Override
    public void stream(String conversationId, Brain brain, String prompt, AIJobType jobType, List<ImageAttachment> images, ChatEventSink eventSink) {
        Instant startedAt = Instant.now();
        long startedNano = System.nanoTime();
        String requestId = diagnosticsRequestId();
        boolean hasImages = images != null && !images.isEmpty();
        try {
            LOGGER.info("[JARVIS][requestId={}][OLLAMA] HTTP_CALL_PREPARING model={} reasoning={}",
                    requestId, brain.model(), brain.reasoningLevel());
            eventSink.publish(StatusChangedEvent.create(ChatEventType.MODEL_LOADING, conversationId, "MODEL_LOADING"));
            // Images go through /api/chat, not /api/generate: some chat-templated multimodal models
            // (confirmed for at least one Gemma build) silently ignore /api/generate's top-level
            // images field - the model runs and answers normally, it just never actually looks at
            // the image, because that field is never referenced by the chat template render path.
            // /api/chat's per-message images field is the transport those templates are wired for.
            String endpoint = normalizeBaseUrl(properties.baseUrl()) + (hasImages ? "/api/chat" : "/api/generate");
            publishCognitive(jobType, CognitiveEventType.MODEL_REQUEST_STARTED, "REQUESTING", "Model request started", "model:" + brain.model(), Map.of(
                    "model", brain.model(),
                    "endpoint", endpoint,
                    "provider", provider(),
                    "reasoningLevel", brain.reasoningLevel().name()
            ));

            try (OllamaRequestCoordinator.Permit ignored = requestCoordinator.acquire(jobType, requestId)) {
                long httpPreparationStarted = System.nanoTime();
                String budgetedPrompt = contextBudgetService.fitPrompt(brain.model(), prompt);
                Object think = resolveThink(brain);
                String requestJson = hasImages
                        ? objectMapper.writeValueAsString(buildChatRequestWithImages(brain, budgetedPrompt, images, think))
                        : objectMapper.writeValueAsString(new OllamaGenerateRequest(
                                brain.model(),
                                budgetedPrompt,
                                true,
                                think,
                                properties.keepAlive(),
                                contextBudgetService.ollamaOptions(),
                                List.of()
                        ));
                if (hasImages) {
                    LOGGER.info("[JARVIS][requestId={}][OLLAMA] Sending images via /api/chat count={} model={}",
                            requestId, images.size(), brain.model());
                }
                // Logs the exact requestJson String already built above - never a second,
                // independently re-serialized copy - so the trace log is guaranteed to match the
                // real HTTP body below, including the post-context-budgeting prompt text.
                AiTraceLogger.logOutboundAiRequest(requestId, brain.model(), endpoint, jobType.name(),
                        brain.reasoningLevel().name(), AiTraceTurnContext.current(), requestJson);
                // No per-request timeout - a slow local model genuinely still generating must
                // never be cut off mid-response purely because a clock ran out (matches the
                // NativeToolLoopService wall-clock timeout, which is disabled by default for the
                // same reason).
                HttpRequest httpRequest = HttpRequest.newBuilder()
                        .uri(URI.create(endpoint))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(requestJson))
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
                streamResponse(conversationId, brain, httpResponse.body(), startedAt, startedNano, ollamaRequestLatencyMs, jobType, eventSink,
                        budgetedPrompt, images == null ? List.of() : images);
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

    @Override
    public ModelResponse toolChat(
            Brain brain,
            List<ModelMessage> messages,
            List<NativeToolDefinition> tools,
            AIJobType jobType
    ) {
        String requestId = diagnosticsRequestId();
        String endpoint = normalizeBaseUrl(properties.baseUrl()) + "/api/chat";
        try {
            LOGGER.info("[JARVIS][requestId={}][OLLAMA] NATIVE_TOOL_CHAT_PREPARING model={} tools={}",
                    requestId, brain.model(), tools == null ? 0 : tools.size());
            publishCognitive(jobType, CognitiveEventType.MODEL_REQUEST_STARTED, "REQUESTING",
                    "Native tool model turn started", "model:" + brain.model(), Map.of(
                            "model", brain.model(),
                            "endpoint", endpoint,
                            "provider", provider(),
                            "toolDefinitions", tools == null ? 0 : tools.size()
                    ));
            try (OllamaRequestCoordinator.Permit ignored = requestCoordinator.acquire(jobType, requestId)) {
                OllamaChatRequest requestBody = new OllamaChatRequest(
                        brain.model(),
                        toOllamaMessages(messages),
                        toOllamaTools(tools),
                        false,
                        resolveThink(brain),
                        properties.keepAlive(),
                        contextBudgetService.ollamaOptions()
                );
                // Serialized exactly once - this same String is what gets logged AND what becomes
                // the HTTP body below, so the trace log can never drift from the real payload (see
                // AiTraceLogger#logOutboundAiRequest javadoc).
                String requestJson = objectMapper.writeValueAsString(requestBody);
                AiTraceLogger.logOutboundAiRequest(requestId, brain.model(), endpoint, jobType.name(),
                        brain.reasoningLevel().name(), AiTraceTurnContext.current(), requestJson);
                long started = System.nanoTime();
                // No per-request timeout - see the identical comment on the streaming chat request
                // above. A native tool-call turn with a large tool schema and a slow local model
                // can legitimately take well over 5 minutes to generate; that used to abort the
                // turn with HttpTimeoutException regardless of how much real progress the model was
                // making, defeating the NativeToolLoopService wall-clock timeout being disabled.
                HttpRequest httpRequest = HttpRequest.newBuilder()
                        .uri(URI.create(endpoint))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                        .build();
                HttpResponse<String> httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                long durationMs = nanosToMillis(System.nanoTime() - started);
                if (httpResponse.statusCode() < 200 || httpResponse.statusCode() >= 300) {
                    LOGGER.error("[JARVIS][requestId={}][OLLAMA] NATIVE_TOOL_CHAT_HTTP_ERROR status={} body={}",
                            requestId, httpResponse.statusCode(), httpResponse.body());
                    // The real reason (e.g. Ollama's own "error parsing tool call: unexpected end of
                    // JSON input" when the model's function-call arguments were malformed/truncated)
                    // must reach the caller, not just the log - NativeToolLoopService needs it to
                    // distinguish a recoverable native-tool-call parsing failure from a genuine
                    // provider/connection failure. Bounded so a huge body is never dumped into an
                    // exception message.
                    throw new OllamaException("Ollama native tool chat failed with status " + httpResponse.statusCode()
                            + ": " + truncate(httpResponse.body(), 500));
                }
                OllamaChatResponse response = objectMapper.readValue(httpResponse.body(), OllamaChatResponse.class);
                ModelResponse modelResponse = toModelResponse(response);
                LOGGER.info("[JARVIS][requestId={}][OLLAMA] NATIVE_TOOL_CHAT_FINISHED durationMs={} contentChars={} toolCalls={}",
                        requestId, durationMs, modelResponse.content().length(), modelResponse.toolCalls().size());
                publishCognitive(jobType, CognitiveEventType.EXECUTION_TRACE, "FINISHED",
                        "Native tool model turn finished", "model:" + brain.model(), Map.of(
                                "stage", "NATIVE_TOOL_MODEL_TURN",
                                "durationMs", durationMs,
                                "toolCalls", modelResponse.toolCalls().size(),
                                "contentCharacters", modelResponse.content().length(),
                                "finishReason", modelResponse.finishReason(),
                                "severity", severity(durationMs)
                        ));
                return modelResponse;
            }
        } catch (JsonProcessingException exception) {
            LOGGER.error("[JARVIS] Ollama native tool JSON error", exception);
            throw new OllamaException("Failed to process Ollama native tool response", exception);
        } catch (IOException exception) {
            LOGGER.error("[JARVIS] Ollama native tool request failed at {}", properties.baseUrl(), exception);
            throw new OllamaException("Failed to communicate with Ollama native tool endpoint", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            LOGGER.error("[JARVIS] Ollama native tool request interrupted", exception);
            throw new OllamaException("Ollama native tool request was interrupted", exception);
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
            ChatEventSink eventSink,
            String budgetedPrompt,
            List<ImageAttachment> images
    ) throws IOException {
        GenerationStreamState state = new GenerationStreamState();
        boolean hasImages = images != null && !images.isEmpty();

        // Scoped to the primary main-model decision turn only - never to internal DEBUG/repair
        // calls (see ModelExecutionStage.parseAction's JSON-repair retry) or background jobs, so
        // a single user message can never trigger more than one budget-limited reasoning stage.
        boolean qwenBudgetTarget = jobType == AIJobType.MAIN_MODEL && qwenThinkingBudgetProperties.matchesTarget(brain.model());
        int configuredMaxTokens = qwenThinkingBudgetProperties.resolveMaxTokens();
        boolean budgetApplies = qwenBudgetTarget && configuredMaxTokens >= 0;

        boolean budgetExceeded = hasImages
                ? readChatStream(inputStream, conversationId, brain, jobType, eventSink, startedNano, state, budgetApplies, configuredMaxTokens)
                : readGenerationStream(inputStream, conversationId, brain, jobType, eventSink, startedNano, state, budgetApplies, configuredMaxTokens);

        boolean thinkingLimited = false;
        if (budgetExceeded && !state.answerStarted) {
            thinkingLimited = true;
            LOGGER.info(
                    "[JARVIS][requestId={}][QWEN_THINKING_BUDGET] model={} mode={} budget={} estimatedThinkingTokens={} -> "
                            + "cancelling reasoning and requesting the final answer directly",
                    diagnosticsRequestId(), brain.model(), qwenThinkingBudgetProperties.getMode(), configuredMaxTokens, state.estimatedThinkingTokens);
            try {
                if (hasImages) {
                    HttpResponse<InputStream> continuationResponse = sendChatContinuationRequest(brain, budgetedPrompt, images);
                    readChatStream(continuationResponse.body(), conversationId, brain, jobType, eventSink, startedNano, state, false, -1);
                } else {
                    HttpResponse<InputStream> continuationResponse = sendContinuationRequest(brain, budgetedPrompt);
                    readGenerationStream(continuationResponse.body(), conversationId, brain, jobType, eventSink, startedNano, state, false, -1);
                }
                // Continuation has thinking disabled, so no further budget accounting is needed or possible.
            } catch (RuntimeException | IOException | InterruptedException exception) {
                if (exception instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                LOGGER.warn(
                        "[JARVIS][requestId={}][QWEN_THINKING_BUDGET] continuation request failed model={} reason={} - "
                                + "falling back to whatever content was already produced",
                        diagnosticsRequestId(), brain.model(), exception.getMessage());
            }
        }

        if (state.thinkingStarted && !state.thinkingFinished) {
            long durationMs = nanosToMillis((state.lastThinkingNano == 0L ? System.nanoTime() : state.lastThinkingNano) - state.firstThinkingNano);
            long finalThinkingChunks = state.thinkingChunks;
            diagnostics(d -> {
                d.setThinkingDurationMs(durationMs);
                d.setThinkingTokensOrChunks(finalThinkingChunks);
            });
            publishCognitive(jobType, CognitiveEventType.THINKING_FINISHED, "FINISHED", "Thinking finished", "model:" + brain.model(), Map.of(
                    "durationMs", durationMs,
                    "chunks", state.thinkingChunks,
                    "characters", state.thinkingCharacters
            ));
        }
        if (state.answerStarted) {
            long answerDurationMs = nanosToMillis(System.nanoTime() - state.firstAnswerNano);
            diagnostics(d -> d.setAnswerStreamingDurationMs(answerDurationMs));
            publishCognitive(jobType, CognitiveEventType.ANSWER_FINISHED, "FINISHED", "Answer finished", "model:" + brain.model(), Map.of(
                    "durationMs", answerDurationMs,
                    "characters", state.answerCharacters,
                    "tokens", state.finalEvalCount == null ? state.answerChunks : state.finalEvalCount
            ));
        }

        long generationTimeMs = Duration.between(startedAt, Instant.now()).toMillis();
        diagnostics(d -> d.setTotalModelRequestMs(generationTimeMs));
        Integer completionTokens = state.finalEvalCount;
        Integer promptTokens = state.finalPromptEvalCount;
        Double tokensPerSecond = tokensPerSecond(completionTokens, generationTimeMs);
        OllamaInferenceMetrics metrics = buildMetrics(
                brain,
                state,
                requestStartToHeadersMs,
                state.firstThinkingTokenMs,
                state.firstAnswerTokenMs
        );
        publishMetrics(jobType, brain, metrics, generationTimeMs,
                qwenBudgetTarget ? new QwenThinkingBudgetOutcome(true, qwenThinkingBudgetProperties.getMode(), configuredMaxTokens, state.estimatedThinkingTokens, thinkingLimited)
                        : QwenThinkingBudgetOutcome.notApplicable());

        LOGGER.info(
                "[JARVIS] Generation time: {} ms, tokens: {}, tokens per second: {}",
                generationTimeMs,
                tokenSummary(state),
                tokensPerSecond == null ? "unavailable" : tokensPerSecond
        );
        publishCognitive(jobType, CognitiveEventType.STREAMING_FINISHED, "FINISHED", "Streaming finished", "model:" + brain.model(), Map.of(
                "generationTimeMs", generationTimeMs,
                "promptTokens", promptTokens == null ? 0 : promptTokens,
                "completionTokens", completionTokens == null ? state.answerChunks : completionTokens,
                "tokensStreamed", state.answerChunks,
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

    /**
     * Reads one native-generate ({@code /api/generate}) stream line-by-line, updating
     * {@code state} and publishing the exact same events the original single-pass implementation
     * did. When {@code budgetApplies}, also accumulates an estimated thinking-token count
     * (chars/4, the same estimation approach already used project-wide in
     * {@code ContextBudgetService} - Ollama does not expose a live per-chunk token count during
     * streaming, only a final total after {@code done:true}) and stops reading as soon as that
     * estimate reaches {@code maxThinkingTokens}.
     *
     * @return true when reading stopped early because the thinking budget was reached (not a
     *         natural {@code done:true})
     */
    private boolean readGenerationStream(
            InputStream inputStream,
            String conversationId,
            Brain brain,
            AIJobType jobType,
            ChatEventSink eventSink,
            long startedNano,
            GenerationStreamState state,
            boolean budgetApplies,
            int maxThinkingTokens
    ) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }

                OllamaGenerateResponse response = objectMapper.readValue(line, OllamaGenerateResponse.class);
                if (Boolean.TRUE.equals(response.done())) {
                    state.finalTotalDurationNanos = response.totalDuration();
                    state.finalLoadDurationNanos = response.loadDuration();
                    state.finalPromptEvalDurationNanos = response.promptEvalDuration();
                    state.finalEvalDurationNanos = response.evalDuration();
                    state.finalEvalCount = response.evalCount();
                    state.finalPromptEvalCount = response.promptEvalCount();
                    return false;
                }

                if (handleThinkingChunk(response.thinking(), conversationId, brain, jobType, eventSink, startedNano, state)
                        && budgetApplies && accumulateThinkingBudget(response.thinking(), state, maxThinkingTokens)) {
                    return true;
                }
                handleContentChunk(response.response(), conversationId, brain, jobType, eventSink, startedNano, state);
            }
        }
        return false;
    }

    /**
     * Reads one native-chat ({@code /api/chat}) stream line-by-line - used only when images are
     * attached, since that is the transport a chat-templated multimodal model actually reads
     * images from. Otherwise behaves identically to {@link #readGenerationStream}, sharing the
     * same per-chunk event publishing and thinking-budget accounting.
     *
     * @return true when reading stopped early because the thinking budget was reached
     */
    private boolean readChatStream(
            InputStream inputStream,
            String conversationId,
            Brain brain,
            AIJobType jobType,
            ChatEventSink eventSink,
            long startedNano,
            GenerationStreamState state,
            boolean budgetApplies,
            int maxThinkingTokens
    ) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }

                OllamaChatResponse response = objectMapper.readValue(line, OllamaChatResponse.class);
                if (Boolean.TRUE.equals(response.done())) {
                    state.finalTotalDurationNanos = response.totalDuration();
                    state.finalLoadDurationNanos = response.loadDuration();
                    state.finalPromptEvalDurationNanos = response.promptEvalDuration();
                    state.finalEvalDurationNanos = response.evalDuration();
                    state.finalEvalCount = response.evalCount();
                    state.finalPromptEvalCount = response.promptEvalCount();
                    return false;
                }

                OllamaChatMessage message = response.message();
                String thinking = message == null ? null : message.thinking();
                String content = message == null ? null : message.content();
                if (handleThinkingChunk(thinking, conversationId, brain, jobType, eventSink, startedNano, state)
                        && budgetApplies && accumulateThinkingBudget(thinking, state, maxThinkingTokens)) {
                    return true;
                }
                handleContentChunk(content, conversationId, brain, jobType, eventSink, startedNano, state);
            }
        }
        return false;
    }

    /**
     * Processes one reasoning-channel chunk, shared by both the generate and chat stream readers.
     *
     * @return true when a non-empty thinking chunk was actually processed (so the caller knows
     *         whether to also run thinking-budget accounting for it)
     */
    private boolean handleThinkingChunk(
            String thinking, String conversationId, Brain brain, AIJobType jobType, ChatEventSink eventSink, long startedNano, GenerationStreamState state
    ) {
        if (thinking == null || thinking.isEmpty()) {
            return false;
        }
        if (!state.thinkingStarted) {
            state.thinkingStarted = true;
            state.firstThinkingNano = System.nanoTime();
            long elapsedMs = nanosToMillis(state.firstThinkingNano - startedNano);
            state.firstThinkingTokenMs = elapsedMs;
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
        state.lastThinkingNano = System.nanoTime();
        state.thinkingChunks++;
        state.thinkingCharacters += thinking.length();
        publishCognitive(jobType, CognitiveEventType.THINKING_TOKEN, "THINKING", thinking, "model:" + brain.model(), Map.of(
                "text", thinking,
                "index", state.thinkingChunks
        ));
        eventSink.publish(ThinkingTokenEvent.create(conversationId, thinking));
        return true;
    }

    /**
     * Accumulates the estimated thinking-token budget for one chunk.
     *
     * @return true once the estimate reaches {@code maxThinkingTokens}
     */
    private boolean accumulateThinkingBudget(String thinking, GenerationStreamState state, int maxThinkingTokens) {
        state.estimatedThinkingTokens += estimateTokens(thinking);
        return state.estimatedThinkingTokens >= maxThinkingTokens;
    }

    /**
     * Processes one answer-channel chunk, shared by both the generate and chat stream readers.
     */
    private void handleContentChunk(
            String token, String conversationId, Brain brain, AIJobType jobType, ChatEventSink eventSink, long startedNano, GenerationStreamState state
    ) {
        if (token == null || token.isEmpty()) {
            return;
        }
        if (state.thinkingStarted && !state.thinkingFinished) {
            state.thinkingFinished = true;
            long durationMs = nanosToMillis((state.lastThinkingNano == 0L ? System.nanoTime() : state.lastThinkingNano) - state.firstThinkingNano);
            long finalThinkingChunks = state.thinkingChunks;
            diagnostics(d -> {
                d.setThinkingDurationMs(durationMs);
                d.setThinkingTokensOrChunks(finalThinkingChunks);
            });
            publishCognitive(jobType, CognitiveEventType.THINKING_FINISHED, "FINISHED", "Thinking finished", "model:" + brain.model(), Map.of(
                    "durationMs", durationMs,
                    "chunks", state.thinkingChunks,
                    "characters", state.thinkingCharacters
            ));
        }
        if (!state.answerStarted) {
            state.answerStarted = true;
            state.firstAnswerNano = System.nanoTime();
            long firstTokenLatencyMs = nanosToMillis(state.firstAnswerNano - startedNano);
            state.firstAnswerTokenMs = firstTokenLatencyMs;
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
        for (String chunk : responseChunks(token)) {
            state.answerChunks++;
            state.answerCharacters += chunk.length();
            publishCognitive(jobType, CognitiveEventType.ANSWER_TOKEN, "TOKEN", chunk, "model:" + brain.model(), Map.of(
                    "text", chunk,
                    "index", state.answerChunks
            ));
            publishCognitive(jobType, CognitiveEventType.TOKEN, "TOKEN", chunk, "model:" + brain.model(), Map.of(
                    "text", chunk,
                    "index", state.answerChunks
            ));
            eventSink.publish(TokenEvent.create(conversationId, chunk));
        }
    }

    /**
     * Estimates tokens from characters using the same chars/4 approximation already used
     * project-wide (see {@code ContextBudgetService.estimateTokens}). This is deliberately an
     * <b>estimate</b>, not an exact tokenizer count - Ollama does not expose a live per-chunk
     * token count during streaming, only a final total after generation completes.
     *
     * @param value text to estimate
     * @return estimated token count
     */
    private int estimateTokens(String value) {
        return (int) Math.ceil((value == null ? 0 : value.length()) / 4.0d);
    }

    /**
     * Resolves the {@code think} value to send for the primary model turn - the reasoning-effort
     * string for models that actually declare {@link ModelCapability#THINKING}, or {@code null}
     * (omitted from the request entirely, see {@code OllamaGenerateRequest.think}) for models that
     * don't. Ollama returns HTTP 400 for a non-thinking model if this field is present at all, so
     * every call site must go through this instead of unconditionally sending the reasoning level.
     */
    private Object resolveThink(Brain brain) {
        if (!activeModelService.activeModelCapabilities().contains(ModelCapability.THINKING)) {
            return null;
        }
        return brain.reasoningLevel().name().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Builds one user message carrying the full prompt and any attached images, and wraps it in a
     * streaming {@code /api/chat} request - the transport that actually gets images to a
     * chat-templated multimodal model's vision encoder.
     */
    private OllamaChatRequest buildChatRequestWithImages(Brain brain, String prompt, List<ImageAttachment> images, Object think) {
        OllamaChatMessage message = new OllamaChatMessage(
                "user", prompt, "", List.of(), "",
                images.stream().map(ImageAttachment::base64Data).toList()
        );
        return new OllamaChatRequest(
                brain.model(),
                List.of(message),
                List.of(),
                true,
                think,
                properties.keepAlive(),
                contextBudgetService.ollamaOptions()
        );
    }

    /**
     * Issues the Qwen thinking-budget continuation call for a text-only ({@code /api/generate})
     * turn: same model and prompt, thinking explicitly disabled, so the model answers directly
     * without re-entering unbounded reasoning. Ollama is stateless per call, so the full prompt
     * must be resent for the model to have context at all.
     */
    private HttpResponse<InputStream> sendContinuationRequest(Brain brain, String budgetedPrompt)
            throws IOException, InterruptedException {
        String endpoint = normalizeBaseUrl(properties.baseUrl()) + "/api/generate";
        OllamaGenerateRequest requestBody = new OllamaGenerateRequest(
                brain.model(),
                budgetedPrompt,
                true,
                Boolean.FALSE,
                properties.keepAlive(),
                contextBudgetService.ollamaOptions(),
                List.of()
        );
        return sendStreamingRequest(endpoint, writeJson(requestBody, "Qwen thinking-budget continuation"), brain);
    }

    /**
     * Issues the Qwen thinking-budget continuation call for an image-attached ({@code /api/chat})
     * turn - same prompt and images, thinking disabled, resent in full since Ollama is stateless
     * per call.
     */
    private HttpResponse<InputStream> sendChatContinuationRequest(Brain brain, String budgetedPrompt, List<ImageAttachment> images)
            throws IOException, InterruptedException {
        String endpoint = normalizeBaseUrl(properties.baseUrl()) + "/api/chat";
        OllamaChatRequest requestBody = buildChatRequestWithImages(brain, budgetedPrompt, images, Boolean.FALSE);
        return sendStreamingRequest(endpoint, writeJson(requestBody, "Qwen thinking-budget continuation"), brain);
    }

    private String writeJson(Object requestBody, String context) throws IOException {
        try {
            return objectMapper.writeValueAsString(requestBody);
        } catch (JsonProcessingException exception) {
            throw new OllamaException("Failed to serialize " + context + " request", exception);
        }
    }

    private HttpResponse<InputStream> sendStreamingRequest(String endpoint, String requestJson, Brain brain)
            throws IOException, InterruptedException {
        // No per-request timeout - see the identical comment on the main chat request above.
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                .build();
        LOGGER.info("[JARVIS][requestId={}][QWEN_THINKING_BUDGET] continuation request sent model={} endpoint={}",
                diagnosticsRequestId(), brain.model(), endpoint);
        HttpResponse<InputStream> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new OllamaException("Qwen thinking-budget continuation request failed with status " + response.statusCode());
        }
        return response;
    }

    /**
     * Mutable accumulator for one logical generation turn, shared across the generate/chat stream
     * readers and (when applicable) the Qwen thinking-budget continuation call - so a continuation
     * resumes into the exact same counters as the original stream instead of resetting them.
     */
    private static final class GenerationStreamState {
        private boolean thinkingStarted;
        private boolean thinkingFinished;
        private boolean answerStarted;
        private long firstThinkingNano;
        private long lastThinkingNano;
        private long firstAnswerNano;
        private long firstThinkingTokenMs;
        private long firstAnswerTokenMs;
        private Long finalTotalDurationNanos;
        private Long finalLoadDurationNanos;
        private Long finalPromptEvalDurationNanos;
        private Long finalEvalDurationNanos;
        private Integer finalEvalCount;
        private Integer finalPromptEvalCount;
        private int thinkingChunks;
        private int answerChunks;
        private int thinkingCharacters;
        private int answerCharacters;
        private int estimatedThinkingTokens;
    }

    /**
     * Qwen thinking-budget outcome for one generation turn, surfaced in MODEL PERFORMANCE only
     * when {@code applicable} - i.e. only for the exact configured target model.
     *
     * @param applicable whether the active model is the configured thinking-budget target
     * @param mode configured mode
     * @param budget resolved token cap (-1 when unlimited)
     * @param estimatedThinkingTokens estimated thinking tokens actually used
     * @param limited whether the budget was reached
     */
    private record QwenThinkingBudgetOutcome(boolean applicable, QwenThinkingBudgetMode mode, int budget, int estimatedThinkingTokens, boolean limited) {
        private static QwenThinkingBudgetOutcome notApplicable() {
            return new QwenThinkingBudgetOutcome(false, null, 0, 0, false);
        }
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

    private List<OllamaChatMessage> toOllamaMessages(List<ModelMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        return messages.stream()
                .map(message -> new OllamaChatMessage(
                        message.role(),
                        message.content(),
                        "",
                        toOllamaToolCalls(message.toolCalls()),
                        message.toolCallId(),
                        message.toolName(),
                        message.images().stream().map(ImageAttachment::base64Data).toList()
                ))
                .toList();
    }

    private List<OllamaToolCall> toOllamaToolCalls(List<ModelToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return List.of();
        }
        return toolCalls.stream()
                .map(call -> new OllamaToolCall(call.id(), "function",
                        new OllamaToolFunction(call.name(), call.arguments())))
                .toList();
    }

    private List<Map<String, Object>> toOllamaTools(List<NativeToolDefinition> tools) {
        if (tools == null || tools.isEmpty()) {
            return List.of();
        }
        return tools.stream()
                .map(tool -> Map.<String, Object>of(
                        "type", "function",
                        "function", Map.of(
                                "name", tool.name(),
                                "description", tool.description(),
                                "parameters", tool.parameters()
                        )))
                .toList();
    }

    private ModelResponse toModelResponse(OllamaChatResponse response) {
        OllamaChatMessage message = response == null ? null : response.message();
        List<ModelToolCall> toolCalls = message == null || message.toolCalls() == null
                ? List.of()
                : message.toolCalls().stream()
                .filter(call -> call.function() != null)
                .map(call -> new ModelToolCall(
                        call.id(),
                        call.function().name(),
                        call.function().arguments()
                ))
                .toList();
        int promptTokens = response == null || response.promptEvalCount() == null ? 0 : response.promptEvalCount();
        int completionTokens = response == null || response.evalCount() == null ? 0 : response.evalCount();
        return new ModelResponse(
                message == null ? "" : message.content(),
                message == null ? "" : message.thinking(),
                toolCalls,
                response == null ? "" : response.doneReason(),
                new ModelUsage(promptTokens, completionTokens, promptTokens + completionTokens)
        );
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

    private java.util.List<String> responseChunks(String token) {
        if (token == null || token.isEmpty()) {
            return java.util.List.of();
        }
        if (token.length() <= 8 || token.isBlank()) {
            return java.util.List.of(token);
        }
        java.util.List<String> chunks = new java.util.ArrayList<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\S+\\s*|\\s+").matcher(token);
        while (matcher.find()) {
            chunks.add(matcher.group());
        }
        return chunks.isEmpty() ? java.util.List.of(token) : chunks;
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

    private String truncate(String value, int maxChars) {
        String safe = value == null ? "" : value;
        return safe.length() <= maxChars ? safe : safe.substring(0, maxChars) + "...";
    }

    private String tokenSummary(GenerationStreamState state) {
        if (state.finalEvalCount == null && state.finalPromptEvalCount == null) {
            return "unavailable";
        }
        return "prompt=%s, completion=%s, loadMs=%s, promptEvalMs=%s, evalMs=%s".formatted(
                state.finalPromptEvalCount,
                state.finalEvalCount,
                nsToMs(state.finalLoadDurationNanos),
                nsToMs(state.finalPromptEvalDurationNanos),
                nsToMs(state.finalEvalDurationNanos)
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
            GenerationStreamState state,
            long requestStartToHeadersMs,
            long firstThinkingTokenMs,
            long firstAnswerTokenMs
    ) {
        long totalDurationMs = nsToMs(state.finalTotalDurationNanos);
        long loadDurationMs = nsToMs(state.finalLoadDurationNanos);
        long promptEvalCount = state.finalPromptEvalCount == null ? 0L : state.finalPromptEvalCount;
        long promptEvalDurationMs = nsToMs(state.finalPromptEvalDurationNanos);
        long evalCount = state.finalEvalCount == null ? 0L : state.finalEvalCount;
        long evalDurationMs = nsToMs(state.finalEvalDurationNanos);
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
                state.finalLoadDurationNanos != null && loadDurationMs < 1_000L,
                bottleneck
        );
    }

    private void publishMetrics(AIJobType jobType, Brain brain, OllamaInferenceMetrics metrics, long requestTotalMs,
            QwenThinkingBudgetOutcome qwenThinkingBudget) {
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
        if (qwenThinkingBudget.applicable()) {
            // Only ever present for the exact configured Qwen thinking-budget target model - every
            // other model's OLLAMA_METRICS payload is completely unaffected.
            metadata.put("thinkingBudgetMode", qwenThinkingBudget.mode().name());
            metadata.put("thinkingBudgetTokens", qwenThinkingBudget.budget());
            metadata.put("thinkingBudgetEstimatedTokens", qwenThinkingBudget.estimatedThinkingTokens());
            metadata.put("thinkingBudgetLimited", qwenThinkingBudget.limited());
        }
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
        logUnexpectedColdStart(jobType, metrics);
    }

    private void logUnexpectedColdStart(AIJobType jobType, OllamaInferenceMetrics metrics) {
        if ((jobType == AIJobType.CHAT || jobType == AIJobType.MAIN_MODEL)
                && warmupRegistry.eagerModelReady(metrics.model())
                && !metrics.modelLikelyWarm()
                && metrics.loadDurationMs() > 1_000L
                && warmupRegistry.markUnexpectedColdStartIfFirst(metrics.model())) {
            LOGGER.warn("[MODEL WARMUP] model={} loadMs={} status=UNEXPECTED_COLD_START",
                    metrics.model(), metrics.loadDurationMs());
        }
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
