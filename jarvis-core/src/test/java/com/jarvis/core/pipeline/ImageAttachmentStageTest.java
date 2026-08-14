package com.jarvis.core.pipeline;

import com.jarvis.common.ai.ImageAttachment;
import com.jarvis.common.dto.AttachmentReference;
import com.jarvis.common.dto.ChatRequest;
import com.jarvis.common.knowledge.KnowledgeMode;
import com.jarvis.core.workspace.DefaultTemporaryWorkspaceService;
import com.jarvis.core.workspace.TemporaryWorkspaceProperties;
import com.jarvis.memory.pipeline.PipelineContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for resolving a request's image attachments into base64 payloads on
 * {@link PipelineContext#images()}, before the model execution stage ever runs.
 */
class ImageAttachmentStageTest {

    @TempDir
    private Path tempDir;

    private DefaultTemporaryWorkspaceService workspaceService;
    private ImageAttachmentStage stage;

    @BeforeEach
    void setUp() {
        TemporaryWorkspaceProperties properties = new TemporaryWorkspaceProperties();
        properties.setRoot(tempDir.resolve("temp-workspaces"));
        properties.setMinimumFreeDiskSpaceBytes(0);
        workspaceService = new DefaultTemporaryWorkspaceService(properties);
        stage = new ImageAttachmentStage(workspaceService);
    }

    @Test
    void resolvesAnImageAttachmentIntoBase64() throws Exception {
        var workspace = workspaceService.createWorkspace("conversation");
        byte[] originalBytes = pngBytes(10, 10);
        var uploaded = workspaceService.storeInput(workspace.workspaceId(), "conversation", List.of(
                new MockMultipartFile("files", "photo.png", "image/png", originalBytes)));

        PipelineContext context = contextWithAttachments(List.of(
                new AttachmentReference(uploaded.getFirst().workspaceId(), uploaded.getFirst().attachmentId())));

        PipelineContext result = stage.execute(context);

        assertThat(result.images()).hasSize(1);
        ImageAttachment image = result.images().getFirst();
        assertThat(image.originalFileName()).isEqualTo("photo.png");
        assertThat(Base64.getDecoder().decode(image.base64Data())).isNotEmpty();
    }

    @Test
    void ignoresTextAttachmentsAndLeavesImagesEmpty() {
        var workspace = workspaceService.createWorkspace("conversation");
        var uploaded = workspaceService.storeInput(workspace.workspaceId(), "conversation", List.of(
                new MockMultipartFile("files", "notes.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8))));

        PipelineContext context = contextWithAttachments(List.of(
                new AttachmentReference(uploaded.getFirst().workspaceId(), uploaded.getFirst().attachmentId())));

        PipelineContext result = stage.execute(context);

        assertThat(result.images()).isEmpty();
    }

    @Test
    void requestWithNoAttachmentsIsUnaffected() {
        PipelineContext context = contextWithAttachments(List.of());

        PipelineContext result = stage.execute(context);

        assertThat(result.images()).isEmpty();
        assertThat(result).isSameAs(context);
    }

    private PipelineContext contextWithAttachments(List<AttachmentReference> attachments) {
        return PipelineContext.initial(
                "conversation", "request-1",
                new ChatRequest("conversation", "Co jest na tym zdjeciu?", null, KnowledgeMode.FAST, attachments),
                event -> { }, event -> { });
    }

    private byte[] pngBytes(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}
