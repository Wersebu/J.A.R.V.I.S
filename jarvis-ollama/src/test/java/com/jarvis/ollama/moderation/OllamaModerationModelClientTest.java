package com.jarvis.ollama.moderation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.api.dto.moderation.ModerationRequest;
import com.jarvis.api.dto.moderation.TechnicalCheckSummary;
import com.jarvis.api.service.moderation.ModerationModelAvailability;
import com.jarvis.ollama.OllamaProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class OllamaModerationModelClientTest {
    @Test void activeLlamaModerationNeverCallsOllama()throws Exception{
        var captured=new AtomicReference<String>();startServer(captured);var client=client();
        var control=mock(com.jarvis.common.ai.InferenceControl.class);var selected=new com.jarvis.common.ai.InferenceControl.Selection("cfg","llamacpp","active.gguf",java.util.Map.of());when(control.selection()).thenReturn(selected);when(control.state()).thenReturn(java.util.Map.of("status","READY"));
        var active=mock(com.jarvis.common.ai.AIProvider.class);when(active.provider()).thenReturn("llamacpp");when(active.toolChat(any(),anyList(),anyList(),any())).thenAnswer(i->{assertThat(com.jarvis.common.ai.InferenceSelectionContext.current()).isEqualTo(selected);return new com.jarvis.common.ai.ModelResponse("{\"decision\":\"CLEAN\"}","",List.of(),"stop",null);});
        org.springframework.beans.factory.ObjectProvider<com.jarvis.common.ai.InferenceControl> engines=mock(org.springframework.beans.factory.ObjectProvider.class);when(engines.getIfAvailable()).thenReturn(control);
        org.springframework.beans.factory.ObjectProvider<com.jarvis.common.ai.AIProvider> providers=mock(org.springframework.beans.factory.ObjectProvider.class);when(providers.orderedStream()).thenAnswer(i->java.util.stream.Stream.of(active));client.routing(engines,providers);
        assertThat(client.availability("old-ollama-model",Duration.ofSeconds(2)).modelAvailable()).isTrue();var result=client.moderate(request(),"Trusted policy","old-ollama-model",Duration.ofSeconds(2));assertThat(result.modelVersion()).isEqualTo("active.gguf");assertThat(captured.get()).isNull();var brain=org.mockito.ArgumentCaptor.forClass(com.jarvis.common.ai.Brain.class);verify(active).toolChat(brain.capture(),anyList(),eq(List.of()),any());assertThat(brain.getValue().configurationId()).isEqualTo("cfg");
    }

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsModerationOnlyChatRequestWithoutTools() throws Exception {
        AtomicReference<String> captured = new AtomicReference<>();
        startServer(captured);
        OllamaModerationModelClient client = client();

        String content = client.moderate(request(), "system prompt", "moderation-model:1", Duration.ofSeconds(2)).content();

        JsonNode sent = objectMapper.readTree(captured.get());
        assertThat(sent.path("model").asText()).isEqualTo("moderation-model:1");
        assertThat(sent.path("stream").asBoolean()).isFalse();
        assertThat(sent.path("think").asText()).isEqualTo("low");
        assertThat(sent.path("think").isBoolean()).isFalse();
        assertThat(sent.path("tools")).isEmpty();
        assertThat(sent.path("options").path("num_predict").asInt()).isGreaterThanOrEqualTo(1_200);
        assertThat(sent.path("format").path("additionalProperties").asBoolean()).isFalse();
        assertThat(sent.path("messages").get(0).path("role").asText()).isEqualTo("system");
        assertThat(sent.path("messages").get(1).path("content").asText()).contains("TOPKIMC_SERVER_PROFILE_MODERATION_PAYLOAD");
        assertThat(content).contains("\"decision\":\"CLEAN\"");
    }

    @Test
    void checksInstalledModelViaTags() throws Exception {
        startServer(new AtomicReference<>());
        ModerationModelAvailability availability = client().availability("moderation-model:1", Duration.ofSeconds(2));

        assertThat(availability.ollamaReachable()).isTrue();
        assertThat(availability.modelAvailable()).isTrue();
    }

    @Test
    void extractsObjectContentFromOllamaEnvelopeWithExtraFields() throws Exception {
        AtomicReference<String> captured = new AtomicReference<>();
        startServer(captured, """
                {"model":"moderation-model:1","created_at":"2026-08-28T00:00:00Z","message":{"role":"assistant","content":{"decision":"CLEAN","risk":"LOW","categories":[],"reasonCode":"NO_VIOLATIONS","summary":"OK","adminReviewRequired":false,"modelVersion":"moderation-model:1","policyVersion":"v1"},"thinking":""},"done":true,"done_reason":"stop","total_duration":1}
                """);
        OllamaModerationModelClient client = client();

        String content = client.moderate(request(), "system prompt", "moderation-model:1", Duration.ofSeconds(2)).content();

        assertThat(content).startsWith("{");
        assertThat(content).contains("\"decision\":\"CLEAN\"");
        assertThat(content).contains("\"categories\":[]");
    }

    private void startServer(AtomicReference<String> captured) throws IOException {
        startServer(captured, """
                {"message":{"role":"assistant","content":"{\\"decision\\":\\"CLEAN\\",\\"risk\\":\\"LOW\\",\\"categories\\":[],\\"reasonCode\\":\\"NO_VIOLATIONS\\",\\"summary\\":\\"OK\\",\\"adminReviewRequired\\":false,\\"modelVersion\\":\\"moderation-model:1\\",\\"policyVersion\\":\\"v1\\"}"},"done":true}
                """);
    }

    private void startServer(AtomicReference<String> captured, String chatResponse) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            captured.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = chatResponse.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/api/tags", exchange -> {
            byte[] body = """
                    {"models":[{"name":"moderation-model:1","model":"moderation-model:1","modified_at":"2026-01-01T00:00:00Z","size":1,"digest":"abc","details":{}}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    private OllamaModerationModelClient client() {
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        return new OllamaModerationModelClient(
                HttpClient.newHttpClient(),
                objectMapper,
                new OllamaProperties(baseUrl, "chat-model:1", "-1m", true, Set.of())
        );
    }

    private ModerationRequest request() {
        return new ModerationRequest(
                "server-1",
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                "SURVIVAL",
                "pl",
                "Serwer",
                "Bezpieczny opis",
                List.of("https://discord.gg/example"),
                List.of(),
                List.of(),
                new TechnicalCheckSummary(15, 0, 0, List.of()),
                "v1"
        );
    }
}
