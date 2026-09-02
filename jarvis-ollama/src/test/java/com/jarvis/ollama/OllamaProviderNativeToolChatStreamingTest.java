package com.jarvis.ollama;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.common.ai.AIJobType;
import com.jarvis.common.ai.Brain;
import com.jarvis.common.ai.BrainType;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression coverage for the reported production bug: a native-tool-loop turn's "thinking" was a
 * total black box for its entire duration (previously up to the hard-coded 5-minute HTTP timeout,
 * now unbounded) because {@link OllamaProvider#toolChat} sent {@code stream: false} and only ever
 * saw the fully-assembled response once the whole call finished. These tests exercise the
 * streaming NDJSON reader directly against a real (loopback) HTTP server, verifying: thinking
 * chunks are published live as {@link CognitiveEventType#THINKING_TOKEN} events (not just visible
 * in the final {@link ModelResponse}), multi-line tool_calls are captured correctly, and a
 * single-chunk response (content and {@code done:true} on the same line) still works - the
 * existing {@link OllamaProviderToolChatImageForwardingTest} already covers that shape end to end.
 */
class OllamaProviderNativeToolChatStreamingTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void thinkingChunksArePublishedLiveDuringANativeToolCallTurn() throws IOException {
        RecordingCognitiveEventBus events = new RecordingCognitiveEventBus();
        startServer(String.join("\n",
                "{\"message\":{\"role\":\"assistant\",\"content\":\"\",\"thinking\":\"Let me check the file\"},\"done\":false}",
                "{\"message\":{\"role\":\"assistant\",\"content\":\"\",\"thinking\":\" before deciding.\"},\"done\":false}",
                "{\"message\":{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\","
                        + "\"function\":{\"name\":\"coding__file_list\",\"arguments\":{\"path\":\".\"}}}]},\"done\":false}",
                "{\"done\":true,\"done_reason\":\"tool_calls\",\"prompt_eval_count\":120,\"eval_count\":18}"
        ) + "\n");

        OllamaProvider provider = newProvider(events);
        Brain brain = new Brain(BrainType.FAST, "ollama", "gemma4:12b", "stub", "", 0L, ReasoningLevel.LOW);

        ModelResponse response = provider.toolChat(brain,
                List.of(ModelMessage.system("You are J.A.R.V.I.S."), ModelMessage.user("list files")),
                List.<NativeToolDefinition>of(), AIJobType.MAIN_MODEL);

        assertThat(response.thinking()).isEqualTo("Let me check the file before deciding.");
        assertThat(response.content()).isEmpty();
        assertThat(response.toolCalls()).hasSize(1);
        assertThat(response.toolCalls().getFirst().name()).isEqualTo("coding__file_list");
        assertThat(response.toolCalls().getFirst().arguments()).containsEntry("path", ".");
        assertThat(response.usage().promptTokens()).isEqualTo(120);
        assertThat(response.usage().completionTokens()).isEqualTo(18);

        // The whole point: thinking must arrive as separate, live events - not one dump at the end.
        List<String> thinkingChunks = events.messagesFor(CognitiveEventType.THINKING_TOKEN);
        assertThat(thinkingChunks).containsExactly("Let me check the file", " before deciding.");
        assertThat(events.messagesFor(CognitiveEventType.THINKING_STARTED)).hasSize(1);
        assertThat(events.messagesFor(CognitiveEventType.THINKING_FINISHED)).hasSize(1);
    }

    @Test
    void streamEndingWithoutADoneMarkerFailsInsteadOfReturningATruncatedAnswer() throws IOException {
        startServer("{\"message\":{\"role\":\"assistant\",\"content\":\"\",\"thinking\":\"still going\"},\"done\":false}\n");

        OllamaProvider provider = newProvider(new RecordingCognitiveEventBus());
        Brain brain = new Brain(BrainType.FAST, "ollama", "gemma4:12b", "stub", "", 0L, ReasoningLevel.LOW);

        org.junit.jupiter.api.function.Executable call = () -> provider.toolChat(brain,
                List.of(ModelMessage.user("hi")), List.<NativeToolDefinition>of(), AIJobType.MAIN_MODEL);

        org.junit.jupiter.api.Assertions.assertThrows(OllamaException.class, call);
    }

    private void startServer(String ndjsonBody) throws IOException {
        byte[] response = ndjsonBody.getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/x-ndjson");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    private OllamaProvider newProvider(CognitiveEventBus eventBus) {
        return new OllamaProvider(
                HttpClient.newHttpClient(),
                new ObjectMapper(),
                new OllamaProperties(baseUrl(), "gemma4:12b", "-1m", true, Set.of()),
                eventBus,
                new OllamaRequestCoordinator(true, eventBus),
                new ModelWarmupRegistry(new ModelStartupProperties()),
                new ContextBudgetService(new AiContextProperties(0, 0)),
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

    private static final class RecordingCognitiveEventBus implements CognitiveEventBus {

        private final List<CognitiveEventType> types = new ArrayList<>();
        private final List<String> messages = new ArrayList<>();

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
            types.add(event);
            messages.add(message);
        }

        List<String> messagesFor(CognitiveEventType type) {
            List<String> matches = new ArrayList<>();
            for (int i = 0; i < types.size(); i++) {
                if (types.get(i) == type) {
                    matches.add(messages.get(i));
                }
            }
            return matches;
        }
    }
}
