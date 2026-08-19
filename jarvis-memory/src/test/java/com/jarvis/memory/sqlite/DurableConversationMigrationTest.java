package com.jarvis.memory.sqlite;

import com.jarvis.memory.cognitive.MemoryProperties;
import com.jarvis.memory.conversation.ConversationRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for the ETAP C migration: existing {@code working_memory} rows (present at
 * upgrade time) must be copied forward into the new durable {@code conversations}/{@code
 * conversation_messages} tables, safely and idempotently, without touching {@code working_memory}
 * itself.
 */
class DurableConversationMigrationTest {

    @TempDir
    Path tempDir;

    @Test
    void existingWorkingMemoryRowsAreCopiedIntoDurableTablesOnFirstStartup() throws SQLException {
        SQLiteConnectionFactory connectionFactory = new SQLiteConnectionFactory(
                new MemoryProperties(tempDir.resolve("jarvis-memory-test.db").toString(), 30, null, null, null, null));
        // Simulate a pre-upgrade database: create only the legacy working_memory table and seed it
        // directly, exactly as it would look on a real, already-running deployment.
        seedLegacyWorkingMemory(connectionFactory);

        new SQLiteMemoryInitializer(connectionFactory).afterPropertiesSet();

        SQLiteConversationRepository conversationRepository = new SQLiteConversationRepository(connectionFactory);
        SQLiteConversationMessageRepository messageRepository = new SQLiteConversationMessageRepository(connectionFactory);

        List<ConversationRecord> conversations = conversationRepository.list();
        assertThat(conversations).extracting(ConversationRecord::id).containsExactlyInAnyOrder("conversation-a", "conversation-b");

        assertThat(messageRepository.getAllMessages("conversation-a")).hasSize(2)
                .extracting(m -> m.content()).containsExactly("hello from A", "hi back");
        assertThat(messageRepository.getAllMessages("conversation-b")).hasSize(1)
                .extracting(m -> m.content()).containsExactly("hello from B");
    }

    @Test
    void reRunningTheMigrationIsIdempotentAndNeverDuplicates() throws SQLException {
        SQLiteConnectionFactory connectionFactory = new SQLiteConnectionFactory(
                new MemoryProperties(tempDir.resolve("jarvis-memory-test.db").toString(), 30, null, null, null, null));
        seedLegacyWorkingMemory(connectionFactory);

        new SQLiteMemoryInitializer(connectionFactory).afterPropertiesSet();
        // Simulates a second Core startup against the same database file.
        new SQLiteMemoryInitializer(connectionFactory).afterPropertiesSet();
        new SQLiteMemoryInitializer(connectionFactory).afterPropertiesSet();

        SQLiteConversationMessageRepository messageRepository = new SQLiteConversationMessageRepository(connectionFactory);
        assertThat(messageRepository.countMessages("conversation-a")).isEqualTo(2);
        assertThat(messageRepository.countMessages("conversation-b")).isEqualTo(1);
    }

    @Test
    void workingMemoryTableItselfIsNeverModifiedByTheMigration() throws SQLException {
        SQLiteConnectionFactory connectionFactory = new SQLiteConnectionFactory(
                new MemoryProperties(tempDir.resolve("jarvis-memory-test.db").toString(), 30, null, null, null, null));
        seedLegacyWorkingMemory(connectionFactory);

        new SQLiteMemoryInitializer(connectionFactory).afterPropertiesSet();

        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM working_memory")) {
            var resultSet = statement.executeQuery();
            resultSet.next();
            assertThat(resultSet.getInt(1)).isEqualTo(3);
        }
    }

    private void seedLegacyWorkingMemory(SQLiteConnectionFactory connectionFactory) throws SQLException {
        try (Connection connection = connectionFactory.openConnection()) {
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS working_memory (
                            id TEXT PRIMARY KEY,
                            conversation_id TEXT NOT NULL,
                            request_id TEXT NOT NULL DEFAULT '',
                            role TEXT NOT NULL,
                            content TEXT NOT NULL,
                            created_at TEXT NOT NULL,
                            sequence_number INTEGER NOT NULL DEFAULT 0,
                            message_type TEXT NOT NULL DEFAULT 'CHAT',
                            status TEXT NOT NULL DEFAULT 'FINAL'
                        )
                        """);
            }
            // Distinct request_id per message - working_memory already carries a pre-existing
            // UNIQUE(request_id, role, message_type) index; reusing one request_id across messages
            // of the same role would violate that constraint before this migration ever runs.
            insertLegacyMessage(connection, java.util.UUID.randomUUID().toString(), "conversation-a", "r1", "USER", "hello from A", 1, Instant.now());
            insertLegacyMessage(connection, java.util.UUID.randomUUID().toString(), "conversation-a", "r2", "ASSISTANT", "hi back", 2, Instant.now().plusSeconds(1));
            insertLegacyMessage(connection, java.util.UUID.randomUUID().toString(), "conversation-b", "r3", "USER", "hello from B", 1, Instant.now().plusSeconds(2));
        }
    }

    private void insertLegacyMessage(Connection connection, String id, String conversationId, String requestId,
            String role, String content, int sequenceNumber, Instant createdAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO working_memory (id, conversation_id, request_id, role, content, created_at, sequence_number, message_type, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'CHAT', 'FINAL')
                """)) {
            statement.setString(1, id);
            statement.setString(2, conversationId);
            statement.setString(3, requestId);
            statement.setString(4, role);
            statement.setString(5, content);
            statement.setString(6, createdAt.toString());
            statement.setInt(7, sequenceNumber);
            statement.executeUpdate();
        }
    }
}
