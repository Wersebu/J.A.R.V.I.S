package com.jarvis.ollama;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.common.ai.AIJobType;
import com.jarvis.common.ai.Brain;
import com.jarvis.common.ai.BrainType;
import com.jarvis.common.ai.ImageAttachment;
import com.jarvis.common.ai.ModelMessage;
import com.jarvis.common.ai.ModelResponse;
import com.jarvis.common.ai.NativeToolDefinition;
import com.jarvis.common.ai.ReasoningLevel;
import com.jarvis.common.event.CognitiveEvent;
import com.jarvis.common.event.CognitiveEventBus;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wire-level regression test for the exact reported bug: images attached to the current user
 * message must actually reach Ollama's {@code /api/chat} payload during native tool-calling turns,
 * not just during the plain (non-tool) {@link OllamaProvider#stream} path.
 */
class OllamaProviderToolChatImageForwardingTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void toolChatForwardsCurrentMessageImagesOnTheUserTurn() throws IOException {
        AtomicReference<String> capturedBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"done\":true}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        ObjectMapper objectMapper = new ObjectMapper();
        OllamaProvider provider = new OllamaProvider(
                HttpClient.newHttpClient(),
                objectMapper,
                new OllamaProperties(baseUrl(), "gemma4:26b", "-1m", true, Set.of()),
                new NoopCognitiveEventBus(),
                new OllamaRequestCoordinator(true, new NoopCognitiveEventBus()),
                new ModelWarmupRegistry(new ModelStartupProperties()),
                new ContextBudgetService(new AiContextProperties(0, 0, 0)),
                new QwenThinkingBudgetProperties(),
                new StubActiveModelService()
        );

        Brain brain = new Brain(BrainType.FAST, "ollama", "gemma4:26b", "stub", "", 0L, ReasoningLevel.LOW);
        ImageAttachment photo = new ImageAttachment("ZmFrZS1pbWFnZS1ieXRlcw==", "sklepy.jpg");
        List<ModelMessage> messages = List.of(
                ModelMessage.system("You are J.A.R.V.I.S."),
                ModelMessage.user("ustaw grafik na sierpien", List.of(photo))
        );

        ModelResponse response = provider.toolChat(brain, messages, List.<NativeToolDefinition>of(), AIJobType.MAIN_MODEL);

        assertThat(response.content()).isEqualTo("ok");
        assertThat(capturedBody.get()).isNotNull();
        OllamaChatRequest sent = objectMapper.readValue(capturedBody.get(), OllamaChatRequest.class);
        OllamaChatMessage sentUserTurn = sent.messages().stream()
                .filter(message -> "user".equals(message.role())).findFirst().orElseThrow();
        assertThat(sentUserTurn.images()).containsExactly("ZmFrZS1pbWFnZS1ieXRlcw==");
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static final class StubActiveModelService implements ActiveModelService {

        @Override
        public String activeModel() {
            return "gemma4:26b";
        }

        @Override
        public Set<ModelCapability> activeModelCapabilities() {
            return Set.of(ModelCapability.VISION, ModelCapability.TOOLS);
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
        public void updateBrain(BrainType brain, String model) {
        }

        @Override
        public void publish(CognitiveEventType event, String status, String message, String nodeId, Map<String, Object> metadata) {
        }
    }
}
