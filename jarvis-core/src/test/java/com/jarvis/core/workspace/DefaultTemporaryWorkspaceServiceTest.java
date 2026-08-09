package com.jarvis.core.workspace;

import com.jarvis.common.context.KnowledgeContext;
import com.jarvis.common.dto.AttachmentReference;
import com.jarvis.common.dto.ChatRequest;
import com.jarvis.common.event.CognitiveEventBus;
import com.jarvis.common.prompt.SystemPromptService;
import com.jarvis.core.service.DefaultPromptBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for temporary workspace and attachment handling.
 */
class DefaultTemporaryWorkspaceServiceTest {

    @TempDir
    private Path tempDir;

    private TemporaryWorkspaceProperties properties;
    private DefaultTemporaryWorkspaceService service;

    @BeforeEach
    void setUp() {
        properties = new TemporaryWorkspaceProperties();
        properties.setRoot(tempDir.resolve("temp-workspaces"));
        properties.setMinimumFreeDiskSpaceBytes(0);
        properties.setMaxFileSizeBytes(1024);
        properties.setMaxWorkspaceSizeBytes(4096);
        properties.setMaxTotalSizeBytes(8192);
        properties.setMaxFilesPerWorkspace(10);
        properties.setPromptMaxCharacters(4096);
        service = new DefaultTemporaryWorkspaceService(properties);
    }

    @Test
    void createsWorkspaceWithExpectedDirectories() {
        var metadata = service.createWorkspace("conversation");

        assertThat(metadata.workspaceId()).isNotBlank();
        assertThat(tempDir.resolve("temp-workspaces").resolve(metadata.workspaceId()).resolve("input")).isDirectory();
        assertThat(tempDir.resolve("temp-workspaces").resolve(metadata.workspaceId()).resolve("extracted")).isDirectory();
        assertThat(tempDir.resolve("temp-workspaces").resolve(metadata.workspaceId()).resolve("output")).isDirectory();
    }

    @Test
    void uploadsMultipleUtf8FilesAndPreservesDuplicateNames() {
        var workspace = service.createWorkspace("conversation");

        var uploaded = service.storeInput(workspace.workspaceId(), "conversation", List.of(
                textFile("PromptBuilder.java", "class PromptBuilder {}\n"),
                textFile("PromptBuilder.java", "class Other {}\n"),
                textFile("notes.md", "Zolc i gesla jazn.\n")
        ));

        assertThat(uploaded).hasSize(3);
        assertThat(uploaded).extracting("originalFileName")
                .containsExactly("PromptBuilder.java", "PromptBuilder.java", "notes.md");
        assertThat(uploaded).extracting("storedFileName").doesNotHaveDuplicates();
        assertThat(service.readAttachment(new AttachmentReference(
                uploaded.get(2).workspaceId(),
                uploaded.get(2).attachmentId()
        )).content()).contains("Zolc");
    }

    @Test
    void blocksTraversalBinaryAndSizeViolationsWithoutPartialAttachments() throws Exception {
        var workspace = service.createWorkspace("conversation");

        var traversal = service.storeInput(workspace.workspaceId(), "conversation", List.of(
                textFile("..\\..\\application.yml", "safe")
        ));

        assertThat(traversal.getFirst().originalFileName()).isEqualTo("application.yml");
        assertThat(Files.exists(tempDir.resolve("application.yml"))).isFalse();

        assertThatThrownBy(() -> service.storeInput(workspace.workspaceId(), "conversation", List.of(
                new MockMultipartFile("files", "image.png", "image/png", new byte[]{0, 1, 2})
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported file type");

        assertThatThrownBy(() -> service.storeInput(workspace.workspaceId(), "conversation", List.of(
                new MockMultipartFile("files", "large.txt", "text/plain", "x".repeat(2048).getBytes(StandardCharsets.UTF_8))
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too large");

        try (var paths = Files.walk(tempDir)) {
            assertThat(paths.map(path -> path.getFileName().toString()).filter(name -> name.endsWith(".partial")).toList()).isEmpty();
        }
    }

    @Test
    void enforcesWorkspaceAndGlobalLimits() {
        properties.setMaxWorkspaceSizeBytes(40);
        var workspace = service.createWorkspace("conversation");

        assertThatThrownBy(() -> service.storeInput(workspace.workspaceId(), "conversation", List.of(
                textFile("a.txt", "a".repeat(80))
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workspace size limit");

        properties.setMaxWorkspaceSizeBytes(4096);
        properties.setMaxTotalSizeBytes(60);
        assertThatThrownBy(() -> service.storeInput(workspace.workspaceId(), "conversation", List.of(
                textFile("b.txt", "b".repeat(80))
        ))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Temporary storage limit");
    }

    @Test
    void cleanupRemovesExpiredWorkspaceButTouchKeepsActiveWorkspace() throws Exception {
        properties.setTtl(Duration.ofMillis(80));
        var expired = service.createWorkspace("expired");
        var active = service.createWorkspace("active");
        service.storeInput(active.workspaceId(), "active", List.of(textFile("active.txt", "hello")));

        Thread.sleep(120);
        service.readAttachment(new AttachmentReference(active.workspaceId(), service.metadata(active.workspaceId()).attachments().getFirst().attachmentId()));

        int removed = service.cleanupExpired();

        assertThat(removed).isEqualTo(1);
        assertThat(tempDir.resolve("temp-workspaces").resolve(expired.workspaceId())).doesNotExist();
        assertThat(tempDir.resolve("temp-workspaces").resolve(active.workspaceId())).exists();
    }

    @Test
    void promptBuilderInjectsMultipleAttachmentsAsDataBoundaries() {
        var workspace = service.createWorkspace("conversation");
        var uploaded = service.storeInput(workspace.workspaceId(), "conversation", List.of(
                textFile("Example.java", "public class Example {}\n"),
                textFile("config.json", "{\"enabled\":true}\n")
        ));
        DefaultPromptBuilder builder = new DefaultPromptBuilder(
                new StaticSystemPromptService(),
                new NoOpEventBus(),
                service,
                properties
        );

        String prompt = builder.buildPrompt(new ChatRequest(
                "conversation",
                "Analyze these files.",
                null,
                null,
                uploaded.stream()
                        .map(metadata -> new AttachmentReference(metadata.workspaceId(), metadata.attachmentId()))
                        .toList()
        ), KnowledgeContext.empty());

        assertThat(prompt).contains("=== TEMPORARY ATTACHMENTS ===");
        assertThat(prompt).contains("Name: Example.java");
        assertThat(prompt).contains("public class Example");
        assertThat(prompt).contains("Name: config.json");
        assertThat(prompt).contains("END ATTACHMENT");
        assertThat(prompt.indexOf("=== TEMPORARY ATTACHMENTS ==="))
                .isLessThan(prompt.indexOf("=== CURRENT USER MESSAGE ==="));
    }

    private MockMultipartFile textFile(String name, String content) {
        return new MockMultipartFile("files", name, "text/plain", content.getBytes(StandardCharsets.UTF_8));
    }

    private static final class StaticSystemPromptService implements SystemPromptService {

        @Override
        public String load() {
            return "Jarvis identity";
        }

        @Override
        public String save(String instructions) {
            return instructions;
        }
    }

    private static final class NoOpEventBus implements CognitiveEventBus {

        @Override
        public void startRequest(String requestId, String conversationId, java.util.function.Consumer<com.jarvis.common.event.CognitiveEvent> sink) {
        }

        @Override
        public void finishRequest() {
        }

        @Override
        public void updateBrain(com.jarvis.common.ai.BrainType brain, String model) {
        }

        @Override
        public void publish(com.jarvis.common.event.CognitiveEventType event, String status, String message, String nodeId, Map<String, Object> metadata) {
        }
    }
}
