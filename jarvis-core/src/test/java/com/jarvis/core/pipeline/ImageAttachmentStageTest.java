package com.jarvis.core.pipeline;

import com.jarvis.common.ai.ImageAttachment;
import com.jarvis.common.dto.AttachmentReference;
import com.jarvis.common.dto.ChatRequest;
import com.jarvis.common.event.CognitiveEvent;
import com.jarvis.common.event.CognitiveEventBus;
import com.jarvis.common.event.CognitiveEventType;
import com.jarvis.common.ai.BrainType;
import com.jarvis.common.image.ConversationImageStatus;
import com.jarvis.common.knowledge.KnowledgeMode;
import com.jarvis.core.workspace.DefaultTemporaryWorkspaceService;
import com.jarvis.core.workspace.TemporaryWorkspaceProperties;
import com.jarvis.memory.cognitive.MemoryProperties;
import com.jarvis.memory.image.ConversationImageProperties;
import com.jarvis.memory.image.ConversationImageResolver;
import com.jarvis.memory.image.SQLiteConversationImageRegistry;
import com.jarvis.memory.pipeline.PipelineContext;
import com.jarvis.memory.sqlite.SQLiteConnectionFactory;
import com.jarvis.memory.sqlite.SQLiteMemoryInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for resolving a request's image attachments into base64 payloads on
 * {@link PipelineContext#images()}, and for conversation-scoped image memory - historical images
 * from earlier messages of the same conversation staying available (and, once expired or missing,
 * honestly reported as unavailable) in later messages.
 */
class ImageAttachmentStageTest {

    @TempDir
    private Path tempDir;

    private DefaultTemporaryWorkspaceService workspaceService;
    private SQLiteConversationImageRegistry registry;
    private ImageAttachmentStage stage;
    private RecordingCognitiveEventBus eventBus;

    @BeforeEach
    void setUp() {
        TemporaryWorkspaceProperties properties = new TemporaryWorkspaceProperties();
        properties.setRoot(tempDir.resolve("temp-workspaces"));
        properties.setMinimumFreeDiskSpaceBytes(0);
        workspaceService = new DefaultTemporaryWorkspaceService(properties);
        registry = newRegistry(Duration.ofMinutes(60));
        eventBus = new RecordingCognitiveEventBus();
        stage = newStage(properties, registry);
    }

    private ImageAttachmentStage newStage(TemporaryWorkspaceProperties workspaceProperties, SQLiteConversationImageRegistry imageRegistry) {
        return new ImageAttachmentStage(workspaceService, imageRegistry, new ConversationImageResolver(),
                conversationImageProperties(Duration.ofMinutes(60)), workspaceProperties, eventBus);
    }

    private SQLiteConversationImageRegistry newRegistry(Duration retention) {
        SQLiteConnectionFactory connectionFactory = new SQLiteConnectionFactory(
                new MemoryProperties(tempDir.resolve("images-" + retention.toNanos() + ".db").toString(), 30, null, null, null, null));
        new SQLiteMemoryInitializer(connectionFactory).afterPropertiesSet();
        return new SQLiteConversationImageRegistry(connectionFactory, conversationImageProperties(retention));
    }

    private ConversationImageProperties conversationImageProperties(Duration retention) {
        return new ConversationImageProperties(true, retention, 8, 16_777_216L,
                ConversationImageProperties.AutoAttachMode.REFERENCED_OR_RECENT);
    }

    @Test
    void resolvesAnImageAttachmentIntoBase64() throws Exception {
        var workspace = workspaceService.createWorkspace("conversation");
        byte[] originalBytes = pngBytes(10, 10);
        var uploaded = workspaceService.storeInput(workspace.workspaceId(), "conversation", List.of(
                new MockMultipartFile("files", "photo.png", "image/png", originalBytes)));

        PipelineContext context = contextWithAttachments("conversation", "req-1", "Co jest na tym zdjeciu?", List.of(
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

        PipelineContext context = contextWithAttachments("conversation", "req-1", "Co to za plik?", List.of(
                new AttachmentReference(uploaded.getFirst().workspaceId(), uploaded.getFirst().attachmentId())));

        PipelineContext result = stage.execute(context);

        assertThat(result.images()).isEmpty();
    }

    @Test
    void requestWithNoAttachmentsAndNoConversationHistoryLeavesImagesEmpty() {
        PipelineContext context = contextWithAttachments("conversation", "req-1", "Jaka jest stolica Francji?", List.of());

        PipelineContext result = stage.execute(context);

        assertThat(result.images()).isEmpty();
    }

    @Test
    void imageUploadedInMessageOneIsAvailableAgainInMessageThreeOfTheSameConversation() throws Exception {
        var workspace = workspaceService.createWorkspace("conversation-1");
        var uploaded = workspaceService.storeInput(workspace.workspaceId(), "conversation-1", List.of(
                new MockMultipartFile("files", "village-map.png", "image/png", pngBytes(10, 10))));
        AttachmentReference reference = new AttachmentReference(uploaded.getFirst().workspaceId(), uploaded.getFirst().attachmentId());

        // Message 1: upload + first analysis.
        stage.execute(contextWithAttachments("conversation-1", "req-1", "Co widac na tym zdjeciu?", List.of(reference)));
        // Message 2: unrelated text, no attachments.
        stage.execute(contextWithAttachments("conversation-1", "req-2", "A jaka jest dzis pogoda?", List.of()));
        // Message 3: refers back to the earlier image without re-uploading it.
        PipelineContext result = stage.execute(contextWithAttachments("conversation-1", "req-3",
                "sprawdz jeszcze raz to zdjecie i porownaj je z tym co ustalilismy", List.of()));

        assertThat(result.images()).hasSize(1);
        assertThat(result.images().getFirst().originalFileName()).isEqualTo("village-map.png");
        assertThat(String.valueOf(result.metadata().get("conversationImagesPromptBlock"))).contains("village-map.png", "AVAILABLE");

        CognitiveEvent summary = eventBus.lastEvent(CognitiveEventType.CONVERSATION_IMAGES);
        assertThat(summary).isNotNull();
        assertThat(summary.metadata()).containsEntry("current", 0).containsEntry("historicalAvailable", 1)
                .containsEntry("selected", 1).containsEntry("selectionReason", "HISTORICAL_IMAGE_REFERENCE");
        assertThat(String.valueOf(summary.metadata().get("images"))).doesNotContainIgnoringCase("base64");
    }

    // Exact reported production regression: "co wyslalem ci wczesniej w zalaczniku?" ("what did I
    // send you earlier in the attachment?") contains no image/photo/screen word at all, only the
    // attachment noun "zalaczniku" - Core previously never detected this as a historical-image
    // reference at all, so the model got only "Status: AVAILABLE" metadata with no pixels and spent
    // several minutes reasoning about whether it could see the image.
    @Test
    void attachmentWordingResolvesToTheSingleAvailableImageWithUnambiguousInspectionFlag() throws Exception {
        var workspace = workspaceService.createWorkspace("conversation-1");
        var uploaded = workspaceService.storeInput(workspace.workspaceId(), "conversation-1", List.of(
                new MockMultipartFile("files", "1000018102.jpg", "image/jpeg", pngBytes(10, 10))));
        AttachmentReference reference = new AttachmentReference(uploaded.getFirst().workspaceId(), uploaded.getFirst().attachmentId());

        stage.execute(contextWithAttachments("conversation-1", "req-1", "co to za zdjecie?", List.of(reference)));
        PipelineContext result = stage.execute(contextWithAttachments("conversation-1", "req-2",
                "co wyslalem ci wczesniej w zalaczniku?", List.of()));

        assertThat(result.images()).hasSize(1);
        ImageAttachment resolved = result.images().getFirst();
        assertThat(resolved.originalFileName()).isEqualTo("1000018102.jpg");
        assertThat(Base64.getDecoder().decode(resolved.base64Data())).isNotEmpty();

        String block = String.valueOf(result.metadata().get("conversationImagesPromptBlock"));
        assertThat(block).contains("1000018102.jpg", "Storage status: AVAILABLE",
                "Passed as native visual input in this request: YES", "Model can inspect image now: YES");
        // The exact bug being fixed: an entry must never claim AVAILABLE while also saying the model
        // cannot currently inspect it, with no explanation of that contradiction.
        assertThat(block).doesNotContain("Passed as native visual input in this request: NO\n  Model can inspect image now: YES");

        CognitiveEvent summary = eventBus.lastEvent(CognitiveEventType.CONVERSATION_IMAGES);
        assertThat(summary).isNotNull();
        assertThat(summary.metadata()).containsEntry("selected", 1);
    }

    @Test
    void anImageFromOneConversationNeverAppearsInAnotherConversation() throws Exception {
        var workspace = workspaceService.createWorkspace("conversation-1");
        var uploaded = workspaceService.storeInput(workspace.workspaceId(), "conversation-1", List.of(
                new MockMultipartFile("files", "secret.png", "image/png", pngBytes(10, 10))));
        AttachmentReference reference = new AttachmentReference(uploaded.getFirst().workspaceId(), uploaded.getFirst().attachmentId());
        stage.execute(contextWithAttachments("conversation-1", "req-1", "co widac na tym zdjeciu", List.of(reference)));

        PipelineContext result = stage.execute(contextWithAttachments("conversation-2", "req-2",
                "sprawdz jeszcze raz to zdjecie", List.of()));

        assertThat(result.images()).isEmpty();
        assertThat(registry.findForConversation("conversation-2")).isEmpty();
    }

    @Test
    void twoImagesInOneMessagePreserveTheirUploadOrder() throws Exception {
        var workspace = workspaceService.createWorkspace("conversation-1");
        var uploaded = workspaceService.storeInput(workspace.workspaceId(), "conversation-1", List.of(
                new MockMultipartFile("files", "first.png", "image/png", pngBytes(10, 10)),
                new MockMultipartFile("files", "second.png", "image/png", pngBytes(12, 12))));

        PipelineContext result = stage.execute(contextWithAttachments("conversation-1", "req-1", "co widac na tych zdjeciach", List.of(
                new AttachmentReference(uploaded.get(0).workspaceId(), uploaded.get(0).attachmentId()),
                new AttachmentReference(uploaded.get(1).workspaceId(), uploaded.get(1).attachmentId()))));

        assertThat(result.images()).extracting(ImageAttachment::originalFileName).containsExactly("first.png", "second.png");
    }

    @Test
    void expiredImageIsNeverBase64EncodedAndIsReportedAsExpired() throws Exception {
        SQLiteConversationImageRegistry shortLived = newRegistry(Duration.ofMillis(1));
        ImageAttachmentStage shortLivedStage = new ImageAttachmentStage(workspaceService, shortLived, new ConversationImageResolver(),
                conversationImageProperties(Duration.ofMillis(1)), workspacePropertiesFor(tempDir), eventBus);

        var workspace = workspaceService.createWorkspace("conversation-1");
        var uploaded = workspaceService.storeInput(workspace.workspaceId(), "conversation-1", List.of(
                new MockMultipartFile("files", "village-map.png", "image/png", pngBytes(10, 10))));
        AttachmentReference reference = new AttachmentReference(uploaded.getFirst().workspaceId(), uploaded.getFirst().attachmentId());
        shortLivedStage.execute(contextWithAttachments("conversation-1", "req-1", "co widac", List.of(reference)));

        Thread.sleep(20);

        PipelineContext result = shortLivedStage.execute(contextWithAttachments("conversation-1", "req-2",
                "sprawdz jeszcze raz to zdjecie", List.of()));

        assertThat(result.images()).isEmpty();
        String block = String.valueOf(result.metadata().get("conversationImagesPromptBlock"));
        assertThat(block).contains("village-map.png", "EXPIRED", "Passed as native visual input in this request: NO");
        assertThat(block.toLowerCase(java.util.Locale.ROOT)).doesNotContain("base64", "iVBOR");
    }

    // The physical file can disappear (deleted by workspace TTL cleanup) even before the
    // configured conversation-image retention elapses - the registry record alone is never proof.
    @Test
    void missingPhysicalFileIsReportedAsMissingDespiteAnAvailableRecord() throws Exception {
        var workspace = workspaceService.createWorkspace("conversation-1");
        var uploaded = workspaceService.storeInput(workspace.workspaceId(), "conversation-1", List.of(
                new MockMultipartFile("files", "village-map.png", "image/png", pngBytes(10, 10))));
        AttachmentReference reference = new AttachmentReference(uploaded.getFirst().workspaceId(), uploaded.getFirst().attachmentId());
        stage.execute(contextWithAttachments("conversation-1", "req-1", "co widac", List.of(reference)));

        // Simulates the physical workspace being removed (e.g. by TemporaryWorkspaceCleanup) while
        // the registry record itself still says AVAILABLE.
        workspaceService.deleteWorkspace(workspace.workspaceId());

        PipelineContext result = stage.execute(contextWithAttachments("conversation-1", "req-2",
                "sprawdz jeszcze raz to zdjecie", List.of()));

        assertThat(result.images()).isEmpty();
        assertThat(registry.findByAttachmentId("conversation-1", reference.attachmentId()))
                .isPresent().get().extracting(com.jarvis.common.image.ConversationImageRecord::status)
                .isEqualTo(ConversationImageStatus.MISSING);
    }

    private TemporaryWorkspaceProperties workspacePropertiesFor(Path root) {
        TemporaryWorkspaceProperties properties = new TemporaryWorkspaceProperties();
        properties.setRoot(root.resolve("temp-workspaces"));
        properties.setMinimumFreeDiskSpaceBytes(0);
        return properties;
    }

    private PipelineContext contextWithAttachments(String conversationId, String requestId, String message, List<AttachmentReference> attachments) {
        return PipelineContext.initial(
                conversationId, requestId,
                new ChatRequest(conversationId, message, null, KnowledgeMode.FAST, attachments),
                event -> { }, event -> { });
    }

    private byte[] pngBytes(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private static final class RecordingCognitiveEventBus implements CognitiveEventBus {

        private final java.util.List<CognitiveEvent> events = new java.util.ArrayList<>();

        CognitiveEvent lastEvent(CognitiveEventType type) {
            for (int index = events.size() - 1; index >= 0; index--) {
                if (events.get(index).event() == type) {
                    return events.get(index);
                }
            }
            return null;
        }

        @Override
        public void startRequest(String requestId, String conversationId, java.util.function.Consumer<CognitiveEvent> sink) {
        }

        @Override
        public void finishRequest() {
        }

        @Override
        public void updateBrain(BrainType brain, String model) {
        }

        @Override
        public void publish(CognitiveEventType event, String status, String message, String nodeId, java.util.Map<String, Object> metadata) {
            events.add(new CognitiveEvent("request-1", "conversation-1", java.time.Instant.now(), event, status, message,
                    null, "stub-model", nodeId, metadata));
        }
    }
}
