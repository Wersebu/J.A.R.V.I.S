package com.jarvis.ollama;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.common.model.ActiveModelService;
import com.jarvis.common.model.ModelCapability;
import com.jarvis.common.model.ModelCatalog;
import com.jarvis.common.model.ModelStartupPolicy;
import com.jarvis.common.model.ModelSwitchResult;
import com.jarvis.common.model.ModelWarmupStatus;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for the startup warmup bug: Core used to always eagerly warm a hardcoded
 * {@code gpt-oss:20b} regardless of which model was actually active, because {@link
 * OllamaStartupModelWarmup} had no dependency on {@link ActiveModelService} at all and {@link
 * ModelStartupProperties#getModels()} hardcoded {@code gpt-oss:20b -> EAGER} as a default. The fix:
 * the warmup runner now warms exactly the model {@link ActiveModelService#activeModel()} resolves -
 * the SAME single source of truth chat requests use - unconditionally and exactly once, regardless
 * of what (if anything) is configured for it in {@code jarvis.model-startup.models}.
 */
class OllamaStartupModelWarmupTest {

    private HttpServer server;
    private AtomicInteger generateCallCount;
    private List<String> generatedModels;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    // TEST W1: ActiveModelService resolves gemma4:26b - warmup targets gemma4:26b, and gpt-oss:20b
    // is never invoked (no hardcoded default model is warmed just because it used to be one).
    @Test
    void warmsTheResolvedActiveModelAndNeverInvokesTheOldHardcodedDefault() throws IOException {
        startGenerateServer();
        OllamaStartupModelWarmup warmup = warmup(new FakeActiveModelService("gemma4:26b"), new ModelStartupProperties());

        warmup.run(new DefaultApplicationArguments());

        assertThat(generatedModels).containsExactly("gemma4:26b");
        assertThat(generatedModels).doesNotContain("gpt-oss:20b");
    }

    // TEST W2: a persisted, installed active model is warmed exactly as resolved.
    @Test
    void warmsAPersistedInstalledActiveModel() throws IOException {
        startGenerateServer();
        OllamaStartupModelWarmup warmup = warmup(new FakeActiveModelService("qwen3.5:9b"), new ModelStartupProperties());

        warmup.run(new DefaultApplicationArguments());

        assertThat(generatedModels).containsExactly("qwen3.5:9b");
    }

    // TEST W3: whatever fallback ActiveModelService itself picked (persisted model invalid/missing)
    // is exactly what gets warmed - the warmup runner never resolves a second, independent fallback.
    @Test
    void warmsExactlyWhateverFallbackActiveModelServiceResolved() throws IOException {
        startGenerateServer();
        // Simulates ActiveModelService having already fallen back internally (e.g. persisted model
        // no longer installed) - activeModel() simply returns whatever it decided, same as always.
        OllamaStartupModelWarmup warmup = warmup(new FakeActiveModelService("first-installed-fallback:1b"), new ModelStartupProperties());

        warmup.run(new DefaultApplicationArguments());

        assertThat(generatedModels).containsExactly("first-installed-fallback:1b");
    }

    // TEST W4: regardless of how many models are configured/installed, the main LLM is warmed
    // exactly once - never once per configured/installed model.
    @Test
    void warmsTheMainLlmExactlyOnceRegardlessOfConfiguredModelCount() throws IOException {
        startGenerateServer();
        ModelStartupProperties properties = new ModelStartupProperties();
        Map<String, ModelStartupProperties.ModelPolicyProperties> models = new LinkedHashMap<>();
        // 13 unrelated configured entries, none of them the active model, none EAGER - exactly the
        // shape of a real installation with many pulled models sitting in config.
        for (int index = 1; index <= 13; index++) {
            models.put("other-model-" + index + ":1b", new ModelStartupProperties.ModelPolicyProperties(ModelStartupPolicy.LAZY, ""));
        }
        properties.setModels(models);
        OllamaStartupModelWarmup warmup = warmup(new FakeActiveModelService("gemma4:26b"), properties);

        warmup.run(new DefaultApplicationArguments());

        assertThat(generateCallCount.get()).isEqualTo(1);
        assertThat(generatedModels).containsExactly("gemma4:26b");
    }

    // TEST W5: Chatterbox/Whisper/Vision stay LAZY (never eagerly warmed) even as the active model
    // changes across runs - the active-model warmup is strictly additive, never touching them.
    @Test
    void secondaryModelsStayLazyAcrossAnActiveModelChange() throws IOException {
        startGenerateServer();
        ModelStartupProperties properties = new ModelStartupProperties();
        ModelWarmupRegistry registry = new ModelWarmupRegistry(properties);
        OllamaStartupModelWarmup firstWarmup = warmup(new FakeActiveModelService("gemma4:26b"), properties, registry);
        firstWarmup.run(new DefaultApplicationArguments());

        assertThat(registry.modelStatuses().getOrDefault("Chatterbox", ModelWarmupStatus.NOT_STARTED)).isEqualTo(ModelWarmupStatus.NOT_STARTED);
        assertThat(registry.modelStatuses().getOrDefault("Whisper", ModelWarmupStatus.NOT_STARTED)).isEqualTo(ModelWarmupStatus.NOT_STARTED);
        assertThat(registry.modelStatuses().getOrDefault("Vision", ModelWarmupStatus.NOT_STARTED)).isEqualTo(ModelWarmupStatus.NOT_STARTED);

        generatedModels.clear();
        generateCallCount.set(0);
        OllamaStartupModelWarmup secondWarmup = warmup(new FakeActiveModelService("qwen3.5:9b"), properties, registry);
        secondWarmup.run(new DefaultApplicationArguments());

        assertThat(generatedModels).containsExactly("qwen3.5:9b");
        assertThat(registry.modelStatuses().getOrDefault("Chatterbox", ModelWarmupStatus.NOT_STARTED)).isEqualTo(ModelWarmupStatus.NOT_STARTED);
        assertThat(registry.modelStatuses().getOrDefault("Whisper", ModelWarmupStatus.NOT_STARTED)).isEqualTo(ModelWarmupStatus.NOT_STARTED);
        assertThat(registry.modelStatuses().getOrDefault("Vision", ModelWarmupStatus.NOT_STARTED)).isEqualTo(ModelWarmupStatus.NOT_STARTED);
    }

    private OllamaStartupModelWarmup warmup(ActiveModelService activeModelService, ModelStartupProperties properties) {
        return warmup(activeModelService, properties, new ModelWarmupRegistry(properties));
    }

    private OllamaStartupModelWarmup warmup(ActiveModelService activeModelService, ModelStartupProperties properties, ModelWarmupRegistry registry) {
        OllamaProperties ollamaProperties = new OllamaProperties(baseUrl(), "unused-default:1b", "-1m", true, Set.of());
        ContextBudgetService contextBudgetService = new ContextBudgetService(new AiContextProperties(16_384, 2_048));
        return new OllamaStartupModelWarmup(
                HttpClient.newHttpClient(), new ObjectMapper(), ollamaProperties, properties, registry,
                contextBudgetService, activeModelService);
    }

    private void startGenerateServer() throws IOException {
        generateCallCount = new AtomicInteger(0);
        generatedModels = new java.util.concurrent.CopyOnWriteArrayList<>();
        ObjectMapper mapper = new ObjectMapper();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/generate", exchange -> {
            generateCallCount.incrementAndGet();
            byte[] requestBytes = exchange.getRequestBody().readAllBytes();
            Map<?, ?> requestBody = mapper.readValue(requestBytes, Map.class);
            generatedModels.add(String.valueOf(requestBody.get("model")));
            String responseJson = "{\"response\":\"OK\",\"done\":true,\"load_duration\":1000000,\"total_duration\":2000000}";
            byte[] bytes = responseJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /**
     * Returns a fixed model name, exactly like {@link DefaultActiveModelService#activeModel()}
     * would after already resolving persisted/fallback selection internally - the warmup runner
     * must never care how that resolution happened, only call this single method.
     */
    private static final class FakeActiveModelService implements ActiveModelService {

        private final AtomicReference<String> model;

        private FakeActiveModelService(String model) {
            this.model = new AtomicReference<>(model);
        }

        @Override
        public String activeModel() {
            return model.get();
        }

        @Override
        public Set<ModelCapability> activeModelCapabilities() {
            return Set.of();
        }

        @Override
        public ModelCatalog catalog() {
            return new ModelCatalog(List.of(), model.get(), true, null);
        }

        @Override
        public ModelSwitchResult switchTo(String requestedModel) {
            model.set(requestedModel);
            return ModelSwitchResult.success(requestedModel);
        }
    }
}
