package com.jarvis.ollama;

import com.jarvis.common.model.ActiveModelService;
import com.jarvis.common.model.ModelStartupPolicy;
import com.jarvis.common.model.ModelWarmupStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Eagerly loads configured startup models into Ollama memory.
 */
@Service
public class OllamaStartupModelWarmup implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(OllamaStartupModelWarmup.class);
    private static final String WARMUP_PROMPT = "J.A.R.V.I.S. startup warmup. Reply with OK.";
    // Used only when the resolved active model has no matching entry in jarvis.model-startup.models
    // (the normal case - that config is for OTHER models' lazy/eager policy, never a list of models
    // to warm up on its own) - the active model always warms unconditionally regardless.
    private static final String DEFAULT_ACTIVE_MODEL_KEEP_ALIVE = "-1m";

    private final HttpClient httpClient;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final OllamaProperties ollamaProperties;
    private final ModelStartupProperties startupProperties;
    private final ModelWarmupRegistry warmupRegistry;
    private final ContextBudgetService contextBudgetService;
    private final ActiveModelService activeModelService;

    /**
     * Creates the startup warmup runner.
     *
     * @param httpClient HTTP client
     * @param objectMapper JSON mapper
     * @param ollamaProperties Ollama properties
     * @param startupProperties model startup properties
     * @param warmupRegistry warmup registry
     * @param activeModelService single source of truth for which model is actually active - the
     *         model warmed at startup must always be the exact same model this resolves, never a
     *         separately/independently resolved one
     */
    public OllamaStartupModelWarmup(
            HttpClient httpClient,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper,
            OllamaProperties ollamaProperties,
            ModelStartupProperties startupProperties,
            ModelWarmupRegistry warmupRegistry,
            ContextBudgetService contextBudgetService,
            ActiveModelService activeModelService
    ) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.ollamaProperties = ollamaProperties;
        this.startupProperties = startupProperties;
        this.warmupRegistry = warmupRegistry;
        this.contextBudgetService = contextBudgetService;
        this.activeModelService = activeModelService;
    }

    @Override
    public void run(ApplicationArguments args) {
        warmupRegistry.warming();
        boolean failed = false;
        // ActiveModelService's own InitializingBean.afterPropertiesSet() already resolved persisted
        // -> installed-validated -> configured-default -> first-installed and logged the result
        // before any ApplicationRunner (this one included) runs - activeModel() here is guaranteed
        // to already reflect that same resolution, so this warms exactly the one model chat
        // requests will actually use, with no separate/duplicate active-model.txt resolution here.
        String activeModel = activeModelService.activeModel();
        if (!warmupActiveModel(activeModel)) {
            failed = true;
        }
        for (Map.Entry<String, ModelStartupProperties.ModelPolicyProperties> entry : startupProperties.getModels().entrySet()) {
            String model = entry.getKey();
            if (model.equalsIgnoreCase(activeModel)) {
                // Already warmed unconditionally above as the resolved active model - a configured
                // EAGER entry for it here would only be a second, redundant warmup call.
                continue;
            }
            ModelStartupProperties.ModelPolicyProperties policy = entry.getValue();
            if (policy.getStartupPolicy() != ModelStartupPolicy.EAGER) {
                LOGGER.info("[MODEL WARMUP] model={} startupPolicy={} status=LAZY", model, policy.getStartupPolicy());
                continue;
            }
            warmupRegistry.markModel(model, ModelWarmupStatus.WARMING);
            try {
                WarmupResult result = warmup(model, policy.getKeepAlive().isBlank() ? DEFAULT_ACTIVE_MODEL_KEEP_ALIVE : policy.getKeepAlive());
                warmupRegistry.markModel(model, ModelWarmupStatus.READY);
                LOGGER.info("[MODEL WARMUP] source=CONFIGURED model={} loadMs={} warmupMs={} status=READY",
                        model, result.loadMs(), result.warmupMs());
            } catch (RuntimeException exception) {
                failed = true;
                warmupRegistry.markModel(model, ModelWarmupStatus.FAILED);
                LOGGER.error("[MODEL WARMUP] model={} status=FAILED error={}", model, exception.getMessage(), exception);
            }
        }
        if (failed) {
            warmupRegistry.failed();
        } else {
            warmupRegistry.markReady();
        }
    }

    /**
     * Warms exactly the resolved active model, unconditionally - never gated on a configured
     * {@code startupPolicy} entry, since the active model is warmed because it IS active, not
     * because some other model happens to be listed as EAGER in {@code jarvis.model-startup.models}.
     *
     * @param activeModel model currently resolved by {@link ActiveModelService}
     * @return true on success or a blank/unresolved active model (nothing to warm); false on failure
     */
    private boolean warmupActiveModel(String activeModel) {
        if (activeModel == null || activeModel.isBlank()) {
            LOGGER.warn("[MODEL WARMUP] source=ACTIVE_MODEL status=SKIPPED reason=NO_ACTIVE_MODEL_RESOLVED");
            return true;
        }
        warmupRegistry.markModel(activeModel, ModelWarmupStatus.WARMING);
        ModelStartupProperties.ModelPolicyProperties configured = startupProperties.getModels().get(activeModel);
        String keepAlive = configured != null && !configured.getKeepAlive().isBlank()
                ? configured.getKeepAlive() : DEFAULT_ACTIVE_MODEL_KEEP_ALIVE;
        try {
            WarmupResult result = warmup(activeModel, keepAlive);
            warmupRegistry.markModel(activeModel, ModelWarmupStatus.READY);
            LOGGER.info("[MODEL WARMUP] source=ACTIVE_MODEL model={} loadMs={} warmupMs={} status=READY",
                    activeModel, result.loadMs(), result.warmupMs());
            return true;
        } catch (RuntimeException exception) {
            warmupRegistry.markModel(activeModel, ModelWarmupStatus.FAILED);
            LOGGER.error("[MODEL WARMUP] source=ACTIVE_MODEL model={} status=FAILED error={}",
                    activeModel, exception.getMessage(), exception);
            return false;
        }
    }

    private WarmupResult warmup(String model, String keepAlive) {
        long startedNano = System.nanoTime();
        String endpoint = normalizeBaseUrl(ollamaProperties.baseUrl()) + "/api/generate";
        OllamaGenerateRequest requestBody = new OllamaGenerateRequest(
                model,
                contextBudgetService.fitPrompt(model, WARMUP_PROMPT),
                false,
                "low",
                keepAlive,
                contextBudgetService.ollamaOptions()
        );
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofMinutes(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new OllamaException("Warmup failed with status " + response.statusCode()
                        + " body=" + response.body());
            }
            OllamaGenerateResponse generateResponse = objectMapper.readValue(response.body(), OllamaGenerateResponse.class);
            return new WarmupResult(nsToMs(generateResponse.loadDuration()), elapsedMs(startedNano));
        } catch (IOException exception) {
            throw new OllamaException("Failed to warm model " + model, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new OllamaException("Warmup interrupted for model " + model, exception);
        }
    }

    private String normalizeBaseUrl(String baseUrl) {
        if (baseUrl.endsWith("/")) {
            return baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl;
    }

    private long nsToMs(Long value) {
        return value == null ? 0L : value / 1_000_000L;
    }

    private long elapsedMs(long startedNano) {
        return (System.nanoTime() - startedNano) / 1_000_000L;
    }

    private record WarmupResult(long loadMs, long warmupMs) {
    }
}
