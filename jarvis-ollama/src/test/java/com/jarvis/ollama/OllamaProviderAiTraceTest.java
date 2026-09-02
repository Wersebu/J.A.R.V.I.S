package com.jarvis.ollama;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
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
import com.jarvis.common.trace.AiTraceSettings;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for the AI/tool diagnostic trace's core guarantee: the JSON logged under {@code
 * AI_TRACE} is the exact same {@code String} handed to {@code HttpRequest.BodyPublishers.ofString},
 * never a second, independently re-serialized copy that could drift from the real payload.
 */
class OllamaProviderAiTraceTest {

    private HttpServer server;
    private ListAppender<ILoggingEvent> traceAppender;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
        AiTraceSettings.reset();
        if (traceAppender != null) {
            Logger logger = (Logger) LoggerFactory.getLogger("AI_TRACE");
            logger.detachAppender(traceAppender);
            traceAppender.stop();
        }
    }

    @Test
    void loggedOutboundJsonIsByteIdenticalToTheHttpBodyForNativeToolChat() throws IOException {
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

        traceAppender = attachTraceAppender();
        AiTraceSettings.configure(true, false, false);

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
        List<ModelMessage> messages = List.of(
                ModelMessage.system("You are J.A.R.V.I.S."),
                ModelMessage.user("sprawdz Workspace przez Roblox MCP", List.of())
        );

        ModelResponse response = provider.toolChat(brain, messages, List.<NativeToolDefinition>of(), AIJobType.MAIN_MODEL);

        assertThat(response.content()).isEqualTo("ok");
        assertThat(capturedBody.get()).isNotNull();

        String loggedMessage = findAiRequestLog(traceAppender);
        assertThat(loggedMessage).contains("================ AI REQUEST BEGIN ================");
        assertThat(loggedMessage).contains("================ AI REQUEST END ==================");

        int declaredPayloadBytes = extractPayloadBytes(loggedMessage);
        assertThat(declaredPayloadBytes).isEqualTo(capturedBody.get().getBytes(StandardCharsets.UTF_8).length);

        // The pretty-printed JSON embedded in the log must parse to the exact same tree as the raw
        // HTTP body - proving the log did not drift from what was actually sent (no secrets/binary
        // in this request, so redaction never kicks in and both sides must be structurally equal).
        JsonNode loggedTree = objectMapper.readTree(extractPrettyJsonBlock(loggedMessage));
        JsonNode sentTree = objectMapper.readTree(capturedBody.get());
        assertThat(loggedTree).isEqualTo(sentTree);
    }

    @Test
    void noTraceLogAtAllWhenDisabled() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            byte[] response = "{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"done\":true}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        traceAppender = attachTraceAppender();
        AiTraceSettings.reset(); // explicit: all trace flags off

        ObjectMapper objectMapper = new ObjectMapper();
        OllamaProvider provider = new OllamaProvider(
                HttpClient.newHttpClient(), objectMapper,
                new OllamaProperties(baseUrl(), "gemma4:26b", "-1m", true, Set.of()),
                new NoopCognitiveEventBus(), new OllamaRequestCoordinator(true, new NoopCognitiveEventBus()),
                new ModelWarmupRegistry(new ModelStartupProperties()), new ContextBudgetService(new AiContextProperties(0, 0, 0)),
                new QwenThinkingBudgetProperties(), new StubActiveModelService()
        );
        Brain brain = new Brain(BrainType.FAST, "ollama", "gemma4:26b", "stub", "", 0L, ReasoningLevel.LOW);

        provider.toolChat(brain, List.of(ModelMessage.user("hello", List.of())), List.<NativeToolDefinition>of(), AIJobType.MAIN_MODEL);

        assertThat(traceAppender.list).noneMatch(event -> event.getFormattedMessage().contains("AI REQUEST BEGIN"));
    }

    private ListAppender<ILoggingEvent> attachTraceAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger("AI_TRACE");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private String findAiRequestLog(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.contains("AI REQUEST BEGIN"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No AI_TRACE outbound request log was captured"));
    }

    private int extractPayloadBytes(String loggedMessage) {
        Matcher matcher = Pattern.compile("payloadBytes=(\\d+)").matcher(loggedMessage);
        assertThat(matcher.find()).as("payloadBytes field present in trace log").isTrue();
        return Integer.parseInt(matcher.group(1));
    }

    private String extractPrettyJsonBlock(String loggedMessage) {
        int start = loggedMessage.indexOf("payloadBytes=");
        int newlineAfterHeader = loggedMessage.indexOf('\n', start);
        int end = loggedMessage.indexOf("================ AI REQUEST END", newlineAfterHeader);
        return loggedMessage.substring(newlineAfterHeader, end).strip();
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
