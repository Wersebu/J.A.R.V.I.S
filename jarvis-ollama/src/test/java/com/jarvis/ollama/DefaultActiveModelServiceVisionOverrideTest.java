package com.jarvis.ollama;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.common.model.ModelCapability;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for {@code jarvis.ollama.vision-capability-overrides}: a model confirmed
 * multimodal on its Ollama library page can still come back from a local {@code /api/tags} without
 * "vision" in its capabilities (an Ollama-side capability auto-detection gap for that
 * architecture/build) - the override must be the only thing that recovers from that without
 * pretending capability detection is provider-driven for every other model too.
 */
class DefaultActiveModelServiceVisionOverrideTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void addsVisionWhenTheActiveModelIsOverriddenButOllamaDidNotReportIt() throws IOException {
        startTagsServer("""
                {"models":[{"name":"gemma4:26b","size":1,
                  "details":{"family":"gemma4","parameter_size":"26B"},
                  "capabilities":["completion","tools"]}]}
                """);

        DefaultActiveModelService service = service(properties("gemma4:26b", Set.of("gemma4:26b")));

        assertThat(service.activeModelCapabilities())
                .contains(ModelCapability.VISION, ModelCapability.TOOLS, ModelCapability.TEXT);
    }

    @Test
    void leavesCapabilitiesUntouchedWhenNoOverrideIsConfigured() throws IOException {
        startTagsServer("""
                {"models":[{"name":"gemma4:26b","size":1,
                  "details":{"family":"gemma4","parameter_size":"26B"},
                  "capabilities":["completion","tools"]}]}
                """);

        DefaultActiveModelService service = service(properties("gemma4:26b", Set.of()));

        assertThat(service.activeModelCapabilities()).doesNotContain(ModelCapability.VISION);
    }

    @Test
    void overrideMatchIsCaseInsensitive() throws IOException {
        startTagsServer("""
                {"models":[{"name":"gemma4:26b","size":1,"details":{},"capabilities":["completion"]}]}
                """);

        DefaultActiveModelService service = service(properties("gemma4:26b", Set.of("GEMMA4:26B")));

        assertThat(service.activeModelCapabilities()).contains(ModelCapability.VISION);
    }

    @Test
    void doesNotDuplicateVisionWhenOllamaAlreadyReportedIt() throws IOException {
        startTagsServer("""
                {"models":[{"name":"llava:13b","size":1,"details":{},"capabilities":["completion","vision"]}]}
                """);

        DefaultActiveModelService service = service(properties("llava:13b", Set.of("llava:13b")));

        assertThat(service.activeModelCapabilities()).containsExactlyInAnyOrder(
                ModelCapability.TEXT, ModelCapability.VISION);
    }

    @Test
    void overrideNamingAModelThatIsNotCurrentlyActiveHasNoEffect() throws IOException {
        startTagsServer("""
                {"models":[{"name":"gpt-oss:20b","size":1,"details":{},"capabilities":["completion","tools"]}]}
                """);

        DefaultActiveModelService service = service(properties("gpt-oss:20b", Set.of("gemma4:26b")));

        assertThat(service.activeModelCapabilities()).doesNotContain(ModelCapability.VISION);
    }

    private DefaultActiveModelService service(OllamaProperties properties) {
        return new DefaultActiveModelService(HttpClient.newHttpClient(), new ObjectMapper(), properties,
                new ByteArrayResource(new byte[0]));
    }

    private OllamaProperties properties(String activeModel, Set<String> visionOverrides) {
        return new OllamaProperties(baseUrl(), activeModel, "-1m", true, visionOverrides);
    }

    private void startTagsServer(String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/tags", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
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
}
