package com.jarvis.memory.image;

import com.jarvis.common.dto.AttachmentMetadata;
import com.jarvis.common.image.ConversationImageRecord;
import com.jarvis.common.image.ConversationImageStatus;
import com.jarvis.memory.cognitive.MemoryProperties;
import com.jarvis.memory.sqlite.SQLiteConnectionFactory;
import com.jarvis.memory.sqlite.SQLiteMemoryInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the durable, SQLite-backed conversation image registry: metadata-only storage,
 * conversation isolation, idempotent registration, and survival across a simulated Core restart.
 */
class SQLiteConversationImageRegistryTest {

    @TempDir
    Path tempDir;

    private SQLiteConnectionFactory connectionFactory;
    private SQLiteConversationImageRegistry registry;

    @BeforeEach
    void setUp() {
        connectionFactory = new SQLiteConnectionFactory(
                new MemoryProperties(tempDir.resolve("images-test.db").toString(), 30, null, null, null, null));
        new SQLiteMemoryInitializer(connectionFactory).afterPropertiesSet();
        registry = new SQLiteConversationImageRegistry(connectionFactory, properties(Duration.ofMinutes(60)));
    }

    @Test
    void registeredImagesPreserveMessageOrder() {
        List<ConversationImageRecord> registered = registry.registerImages("conversation-1", "message-1", List.of(
                attachment("attachment-a", "a.png"), attachment("attachment-b", "b.png")));

        assertThat(registered).extracting(ConversationImageRecord::attachmentId).containsExactly("attachment-a", "attachment-b");
        assertThat(registered).extracting(ConversationImageRecord::ordinalInMessage).containsExactly(1, 2);
        assertThat(registered).extracting(ConversationImageRecord::conversationLabel).containsExactly("image-1", "image-2");
    }

    @Test
    void registeringTheSameAttachmentTwiceIsANoOp() {
        registry.registerImages("conversation-1", "message-1", List.of(attachment("attachment-a", "a.png")));
        List<ConversationImageRecord> secondAttempt = registry.registerImages("conversation-1", "message-1",
                List.of(attachment("attachment-a", "a.png")));

        assertThat(secondAttempt).hasSize(1);
        assertThat(registry.findForConversation("conversation-1")).hasSize(1);
    }

    @Test
    void anImageRegisteredForOneConversationIsNeverVisibleFromAnother() {
        registry.registerImages("conversation-1", "message-1", List.of(attachment("attachment-a", "a.png")));

        assertThat(registry.findForConversation("conversation-2")).isEmpty();
        assertThat(registry.findByAttachmentId("conversation-2", "attachment-a")).isEmpty();
        assertThat(registry.findByAttachmentId("conversation-1", "attachment-a")).isPresent();
    }

    @Test
    void unknownOlderConversationWithNoImageMetadataReturnsEmptyNotAnError() {
        assertThat(registry.findForConversation("never-had-images")).isEmpty();
    }

    @Test
    void expireOlderThanMarksOnlyPastRetentionRecordsAsExpired() {
        SQLiteConversationImageRegistry shortRetention = new SQLiteConversationImageRegistry(
                connectionFactory, properties(Duration.ofMillis(1)));
        shortRetention.registerImages("conversation-1", "message-1", List.of(attachment("attachment-a", "a.png")));
        registry.registerImages("conversation-1", "message-2", List.of(attachment("attachment-b", "b.png")));

        int expired = registry.expireOlderThan(Instant.now().plusSeconds(1));

        assertThat(expired).isEqualTo(1);
        Optional<ConversationImageRecord> a = registry.findByAttachmentId("conversation-1", "attachment-a");
        Optional<ConversationImageRecord> b = registry.findByAttachmentId("conversation-1", "attachment-b");
        assertThat(a).isPresent().get().extracting(ConversationImageRecord::status).isEqualTo(ConversationImageStatus.EXPIRED);
        assertThat(b).isPresent().get().extracting(ConversationImageRecord::status).isEqualTo(ConversationImageStatus.AVAILABLE);
    }

    @Test
    void deleteConversationRemovesItsImageMetadataOnly() {
        registry.registerImages("conversation-1", "message-1", List.of(attachment("attachment-a", "a.png")));
        registry.registerImages("conversation-2", "message-1", List.of(attachment("attachment-c", "c.png")));

        int deleted = registry.deleteConversation("conversation-1");

        assertThat(deleted).isEqualTo(1);
        assertThat(registry.findForConversation("conversation-1")).isEmpty();
        assertThat(registry.findForConversation("conversation-2")).hasSize(1);
    }

    // Simulates a Core restart: a brand-new connection factory/registry against the same database
    // file, proving metadata survives - the record itself is never proof the physical file does.
    @Test
    void metadataSurvivesASimulatedCoreRestart() {
        registry.registerImages("conversation-1", "message-1", List.of(attachment("attachment-a", "a.png")));

        SQLiteConnectionFactory restarted = new SQLiteConnectionFactory(
                new MemoryProperties(tempDir.resolve("images-test.db").toString(), 30, null, null, null, null));
        new SQLiteMemoryInitializer(restarted).afterPropertiesSet();
        SQLiteConversationImageRegistry afterRestart = new SQLiteConversationImageRegistry(restarted, properties(Duration.ofMinutes(60)));

        assertThat(afterRestart.findForConversation("conversation-1")).extracting(ConversationImageRecord::attachmentId)
                .containsExactly("attachment-a");
    }

    // No base64/byte payload column exists on the record type retrieved from the database at all -
    // this proves by construction that a round trip through SQLite can never carry image bytes.
    @Test
    void storedAndRetrievedRecordsCarryOnlyMetadataNeverImageBytes() {
        registry.registerImages("conversation-1", "message-1", List.of(attachment("attachment-a", "a.png")));

        ConversationImageRecord retrieved = registry.findByAttachmentId("conversation-1", "attachment-a").orElseThrow();

        assertThat(retrieved.originalFileName()).isEqualTo("a.png");
        assertThat(retrieved.mediaType()).isEqualTo("png");
        assertThat(retrieved.toString()).doesNotContainIgnoringCase("base64");
    }

    private ConversationImageProperties properties(Duration retention) {
        return new ConversationImageProperties(true, retention, 8, 16_777_216L,
                ConversationImageProperties.AutoAttachMode.REFERENCED_OR_RECENT);
    }

    private AttachmentMetadata attachment(String attachmentId, String fileName) {
        return new AttachmentMetadata(attachmentId, "workspace-1", fileName, fileName, "png", "image/png", 1000L, Instant.now());
    }
}
