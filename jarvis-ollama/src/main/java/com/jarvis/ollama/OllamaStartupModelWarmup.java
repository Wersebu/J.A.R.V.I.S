package com.jarvis.ollama;

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

    private final HttpClient httpClient;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final OllamaProperties ollamaProperties;
    private final ModelStartupProperties startupProperties;
    private final ModelWarmupRegistry warmupRegistry;

    /**
     * Creates the startup warmup runner.
     *
     * @param httpClient HTTP client
     * @param objectMapper JSON mapper
     * @param ollamaProperties Ollama properties
     * @param startupProperties model startup properties
     * @param warmupRegistry warmup registry
     */
    public OllamaStartupModelWarmup(
            HttpClient httpClient,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper,
            OllamaProperties ollamaProperties,
            ModelStartupProperties startupProperties,
            ModelWarmupRegistry warmupRegistry
    ) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.ollamaProperties = ollamaProperties;
        this.startupProperties = startupProperties;
        this.warmupRegistry = warmupRegistry;
    }

    @Override
    public void run(ApplicationArguments args) {
        warmupRegistry.warming();
        boolean failed = false;
        for (Map.Entry<String, ModelStartupProperties.ModelPolicyProperties> entry : startupProperties.getModels().entrySet()) {
            String model = entry.getKey();
            ModelStartupProperties.ModelPolicyProperties policy = entry.getValue();
            if (policy.getStartupPolicy() != ModelStartupPolicy.EAGER) {
                LOGGER.info("[MODEL WARMUP] model={} startupPolicy={} status=LAZY", model, policy.getStartupPolicy());
                continue;
            }
            warmupRegistry.markModel(model, ModelWarmupStatus.WARMING);
            try {
                WarmupResult result = warmup(model, policy);
                warmupRegistry.markModel(model, ModelWarmupStatus.READY);
                LOGGER.info("[MODEL WARMUP] model={} loadMs={} warmupMs={} status=READY",
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

    private WarmupResult warmup(String model, ModelStartupProperties.ModelPolicyProperties policy) {
        long startedNano = System.nanoTime();
        String endpoint = normalizeBaseUrl(ollamaProperties.baseUrl()) + "/api/generate";
        String keepAlive = policy.getKeepAlive().isBlank() ? "-1m" : policy.getKeepAlive();
        OllamaGenerateRequest requestBody = new OllamaGenerateRequest(model, WARMUP_PROMPT, false, "low", keepAlive);
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
