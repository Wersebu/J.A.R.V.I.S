package com.jarvis.ollama;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.common.event.CognitiveEventBus;
import com.jarvis.common.event.CognitiveEvent;
import com.jarvis.common.event.CognitiveEventType;
import com.jarvis.common.model.ActiveModelService;
import com.jarvis.common.model.ModelCapability;
import com.jarvis.common.model.ModelCatalog;
import com.jarvis.common.model.ModelSwitchResult;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression coverage for {@link OllamaProvider#describeImage}: the dedicated vision-model
 * pathway used by {@code coding__browser_screenshot_describe} so a non-vision text model driving
 * the agent loop can ask a targeted question about a screenshot. Exercises the real {@code
 * /api/generate} HTTP contract - the request shape (model, image, {@code num_gpu} option) and the
 * response parsing - against a real loopback {@link HttpServer}, since this bypasses the whole
 * streaming/cognitive-event machinery the rest of {@link OllamaProvider} uses.
 */
class OllamaProviderDescribeImageTest {

    private HttpServer server;
    private String capturedRequestBody;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void describeImageSendsTheImageAndQuestionAndForcesCpuByDefault() throws IOException {
        startServer(200, "{\"model\":\"moondream\",\"done\":true,\"response\":\"A red button in the top-right corner.\"}");
        OllamaProvider provider = newProvider();

        String answer = provider.describeImage("moondream", "describe precisely the top-right corner",
                "iVBORw0KGgoAAAANS", true);

        assertThat(answer).isEqualTo("A red button in the top-right corner.");
        ObjectMapper mapper = new ObjectMapper();
        JsonNode sent = mapper.readTree(capturedRequestBody);
        assertThat(sent.path("model").asText()).isEqualTo("moondream");
        assertThat(sent.path("prompt").asText()).isEqualTo("describe precisely the top-right corner");
        assertThat(sent.path("images").get(0).asText()).isEqualTo("iVBORw0KGgoAAAANS");
        assertThat(sent.path("stream").asBoolean()).isFalse();
        assertThat(sent.path("options").path("num_gpu").asInt()).isEqualTo(0);
    }

    @Test
    void describeImageOmitsNumGpuWhenForceCpuIsFalse() throws IOException {
        startServer(200, "{\"done\":true,\"response\":\"ok\"}");
        OllamaProvider provider = newProvider();

        provider.describeImage("moondream", "question", "aGVsbG8=", false);

        ObjectMapper mapper = new ObjectMapper();
        JsonNode sent = mapper.readTree(capturedRequestBody);
        assertThat(sent.path("options").has("num_gpu")).isFalse();
    }

    @Test
    void describeImageThrowsOnHttpError() throws IOException {
        startServer(500, "model not found");
        OllamaProvider provider = newProvider();

        assertThatThrownBy(() -> provider.describeImage("missing-model", "question", "aGVsbG8=", true))
                .isInstanceOf(OllamaException.class)
                .hasMessageContaining("missing-model")
                .hasMessageContaining("500");
    }

    private void startServer(int status, String body) throws IOException {
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/generate", exchange -> {
            capturedRequestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    private OllamaProvider newProvider() {
        return new OllamaProvider(
                HttpClient.newHttpClient(),
                new ObjectMapper(),
                new OllamaProperties(baseUrl(), "gemma4:12b", "-1m", true, Set.of()),
                new NoopCognitiveEventBus(),
                new OllamaRequestCoordinator(true, new NoopCognitiveEventBus()),
                new ModelWarmupRegistry(new ModelStartupProperties()),
                new ContextBudgetService(new AiContextProperties(0, 0, 0)),
                new QwenThinkingBudgetProperties(),
                new StubActiveModelService()
        );
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static final class StubActiveModelService implements ActiveModelService {

        @Override
        public String activeModel() {
            return "gemma4:12b";
        }

        @Override
        public Set<ModelCapability> activeModelCapabilities() {
            return Set.of(ModelCapability.TOOLS);
        }

        @Override
        public ModelCatalog catalog() {
            return new ModelCatalog(List.of(), activeModel(), true, null);
        }

        @Override
        public ModelSwitchResult switchTo(String requestedModel) {
            return ModelSwitchResult.rejected(activeModel(), "not supported in test");
        }
    }

    private static final class NoopCognitiveEventBus implements CognitiveEventBus {

        @Override
        public void startRequest(String requestId, String conversationId, Consumer<CognitiveEvent> sink) {
        }

        @Override
        public void finishRequest() {
        }

        @Override
        public void updateBrain(com.jarvis.common.ai.BrainType brain, String model) {
        }

        @Override
        public void publish(CognitiveEventType event, String status, String message, String nodeId, Map<String, Object> metadata) {
        }
    }
}
