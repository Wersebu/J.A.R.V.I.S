package com.jarvis.memory.sqlite;

import com.jarvis.memory.cognitive.MemoryProperties;
import com.jarvis.memory.conversation.ConversationRecord;
import com.jarvis.common.auth.CurrentUserContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for the durable conversation metadata store (ETAP C).
 */
class SQLiteConversationRepositoryTest {

    @TempDir
    Path tempDir;

    private SQLiteConversationRepository repository;

    @BeforeEach
    void setUp() {
        SQLiteConnectionFactory connectionFactory = new SQLiteConnectionFactory(
                new MemoryProperties(tempDir.resolve("jarvis-memory-test.db").toString(), 3, null, null, null, null));
        new SQLiteMemoryInitializer(connectionFactory).afterPropertiesSet();
        repository = new SQLiteConversationRepository(connectionFactory);
    }

    @Test
    void createIfAbsentCreatesADefaultTitledRecordOnFirstCallAndReusesItAfter() {
        Instant now = Instant.now();
        ConversationRecord created = repository.createIfAbsent("conversation-1", now);
        assertThat(created.id()).isEqualTo("conversation-1");
        assertThat(created.title()).isEqualTo(ConversationRecord.DEFAULT_TITLE);
        assertThat(created.archived()).isFalse();

        ConversationRecord reused = repository.createIfAbsent("conversation-1", now.plusSeconds(60));
        assertThat(reused.createdAt()).isEqualTo(created.createdAt());
    }

    @Test
    void findReturnsEmptyForAnUnknownConversation() {
        assertThat(repository.find("unknown")).isEmpty();
    }

    @Test
    void renameSetsAManualTitleThatCreateIfAbsentNeverOverwrites() {
        Instant now = Instant.now();
        repository.createIfAbsent("conversation-1", now);
        repository.rename("conversation-1", "Roblox project folders");

        ConversationRecord afterRename = repository.createIfAbsent("conversation-1", now.plusSeconds(60));
        assertThat(afterRename.title()).isEqualTo("Roblox project folders");
    }

    @Test
    void archiveIsReversibleAndNeverDeletesTheRecord() {
        Instant now = Instant.now();
        repository.createIfAbsent("conversation-1", now);
        repository.setArchived("conversation-1", true);
        assertThat(repository.find("conversation-1")).get().extracting(ConversationRecord::archived).isEqualTo(true);

        repository.setArchived("conversation-1", false);
        assertThat(repository.find("conversation-1")).get().extracting(ConversationRecord::archived).isEqualTo(false);
    }

    @Test
    void updateLastModelAndRollingSummaryPersist() {
        Instant now = Instant.now();
        repository.createIfAbsent("conversation-1", now);
        repository.updateLastModel("conversation-1", "gemma4:26b");
        repository.updateRollingSummary("conversation-1", "User is planning a Roblox project.", 12L);

        ConversationRecord record = repository.find("conversation-1").orElseThrow();
        assertThat(record.lastModel()).isEqualTo("gemma4:26b");
        assertThat(record.rollingSummary()).isEqualTo("User is planning a Roblox project.");
        assertThat(record.summaryUntilSequence()).isEqualTo(12L);
    }

    @Test
    void listOrdersByMostRecentlyUpdatedFirst() {
        Instant base = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        repository.createIfAbsent("older", base);
        repository.createIfAbsent("newer", base);
        repository.touch("older", base.plusSeconds(10));
        repository.touch("newer", base.plusSeconds(20));

        List<ConversationRecord> records = repository.list();
        assertThat(records).hasSize(2);
        assertThat(records.get(0).id()).isEqualTo("newer");
        assertThat(records.get(1).id()).isEqualTo("older");
    }

    @Test
    void deleteRemovesTheRecordAndIsIdempotent() {
        repository.createIfAbsent("conversation-1", Instant.now());
        assertThat(repository.delete("conversation-1")).isEqualTo(1);
        assertThat(repository.find("conversation-1")).isEmpty();
        assertThat(repository.delete("conversation-1")).isEqualTo(0);
    }

    @Test
    void touchUpdatesTheUpdatedAtTimestampWithoutTouchingCreatedAt() {
        Instant createdAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        repository.createIfAbsent("conversation-1", createdAt);
        Instant touchedAt = createdAt.plusSeconds(120);
        repository.touch("conversation-1", touchedAt);

        Optional<ConversationRecord> record = repository.find("conversation-1");
        assertThat(record).isPresent();
        assertThat(record.get().createdAt()).isEqualTo(createdAt);
        assertThat(record.get().updatedAt()).isEqualTo(touchedAt);
    }

    @Test
    void conversationsAreIsolatedPerAuthenticatedUser() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        CurrentUserContext.runAs("user-a", () -> {
            repository.createIfAbsent("conversation-a", now);
            repository.rename("conversation-a", "User A conversation");
        });
        CurrentUserContext.runAs("user-b", () -> {
            repository.createIfAbsent("conversation-b", now.plusSeconds(1));
            repository.rename("conversation-b", "User B conversation");
        });

        CurrentUserContext.runAs("user-a", () -> {
            assertThat(repository.find("conversation-a")).get().extracting(ConversationRecord::title).isEqualTo("User A conversation");
            assertThat(repository.find("conversation-b")).isEmpty();
            assertThat(repository.list()).extracting(ConversationRecord::title).containsExactly("User A conversation");
        });
        CurrentUserContext.runAs("user-b", () -> {
            assertThat(repository.find("conversation-b")).get().extracting(ConversationRecord::title).isEqualTo("User B conversation");
            assertThat(repository.find("conversation-a")).isEmpty();
            assertThat(repository.list()).extracting(ConversationRecord::title).containsExactly("User B conversation");
        });
    }
}
