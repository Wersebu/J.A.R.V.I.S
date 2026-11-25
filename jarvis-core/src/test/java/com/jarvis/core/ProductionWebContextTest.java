package com.jarvis.core;

import com.jarvis.api.controller.HealthController;
import com.jarvis.api.auth.AuthController;
import com.jarvis.api.companion.CompanionController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Real servlet context and controllers: WebEnvironment.NONE misses mapping conflicts. */
@SpringBootTest(classes = JarvisApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.address=127.0.0.1", "knowledge.watch=false", "jarvis.mcp.enabled=false",
                "jarvis.memory.background.enabled=false", "jarvis.memory.autonomous.enabled=false",
                "jarvis.memory.dreaming.enabled=false", "jarvis.companion.history.enabled=false",
                "jarvis.diagnostics.conversation-trace.enabled=false"})
class ProductionWebContextTest {
    private static final Path ROOT = isolatedRoot();
    @Autowired ApplicationContext context;
    @Autowired TestRestTemplate http;
    // Replace external IO only; all production controllers/services stay in the bean graph.
    @org.springframework.boot.test.mock.mockito.MockBean
    java.net.http.HttpClient ollamaHttpClient;

    private static Path isolatedRoot() {
        try { return Files.createTempDirectory("jarvis-production-context-"); }
        catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
    }

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry p) {
        p.add("jarvis.memory.database-path", () -> ROOT.resolve("memory.db").toString());
        p.add("knowledge.root", () -> ROOT.resolve("knowledge").toString());
        p.add("knowledge.vault.index-database", () -> ROOT.resolve("index.db").toString());
        p.add("jarvis.workspace.root", () -> ROOT.resolve("workspaces").toString());
        p.add("jarvis.ai.identity-file", () -> "file:" + ROOT.resolve("identity.md"));
        p.add("jarvis.model.active-model-file", () -> ROOT.resolve("active-model.txt").toString());
    }

    @Test
    void productionControllersOwnHealthAndAuthenticationWithoutIntegrationBeans() {
        var gateway=context.getBean(com.jarvis.common.ai.ModelGateway.class);
        assertThat(gateway.snapshot()).containsEntry("configuredConcurrency",4).containsEntry("reservedInteractive",1);
        assertThat(context.getBeansOfType(com.jarvis.common.companion.CompanionTaskSubmission.class)).hasSize(1);
        assertThat(context.getBeansOfType(com.jarvis.common.ai.InferenceContextPolicy.class)).hasSize(1);
        assertThat(context.getBeansOfType(HealthController.class)).hasSize(1);
        assertThat(context.getBeansOfType(AuthController.class)).hasSize(1);
        assertThat(context.getBeansOfType(CompanionController.class)).hasSize(1);
        var mapping = context.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class);
        var health = mapping.getHandlerMethods().entrySet().stream()
                .filter(e -> e.getKey().getPatternValues().contains("/api/health")).toList();
        assertThat(health).hasSize(1);
        assertThat(health.getFirst().getValue().getBeanType()).isEqualTo(HealthController.class);
        assertThat(List.of(context.getBeanDefinitionNames()).stream()
                .map(context::getType).filter(java.util.Objects::nonNull)
                .map(Class::getName).filter(n -> n.startsWith("com.jarvis.integration."))).isEmpty();
        var response = http.getForEntity("/api/health", String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("\"modelStatus\"").doesNotContain("SIMULATED", "NONPRODUCTION");
        assertThat(http.getForEntity("/api/v1/companion/capabilities", String.class).getStatusCode().value())
                .isEqualTo(401);
        assertThat(http.getForEntity("/api/v1/auth/bootstrap-status", String.class).getBody())
                .contains("\"bootstrapRequired\":true");
    }
}
