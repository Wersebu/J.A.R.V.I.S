package com.jarvis.memory.sqlite;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Initializes SQLite tables used by cognitive memory.
 */
@Component
public class SQLiteMemoryInitializer implements InitializingBean {

    private static final Logger LOGGER = LoggerFactory.getLogger(SQLiteMemoryInitializer.class);

    private final SQLiteConnectionFactory connectionFactory;

    /**
     * Creates the SQLite memory initializer.
     *
     * @param connectionFactory connection factory
     */
    public SQLiteMemoryInitializer(SQLiteConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    /**
     * Creates memory tables during application startup.
     */
    @Override
    public void afterPropertiesSet() {
        try (Connection connection = connectionFactory.openConnection();
             Statement statement = connection.createStatement()) {
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
            addColumnIfMissing(statement, "working_memory", "request_id", "TEXT NOT NULL DEFAULT ''");
            addColumnIfMissing(statement, "working_memory", "sequence_number", "INTEGER NOT NULL DEFAULT 0");
            addColumnIfMissing(statement, "working_memory", "message_type", "TEXT NOT NULL DEFAULT 'CHAT'");
            addColumnIfMissing(statement, "working_memory", "status", "TEXT NOT NULL DEFAULT 'FINAL'");
            statement.executeUpdate("""
                    CREATE UNIQUE INDEX IF NOT EXISTS idx_working_memory_request_role_type
                    ON working_memory(request_id, role, message_type)
                    WHERE request_id <> ''
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS semantic_memory (
                        id TEXT PRIMARY KEY,
                        subject TEXT NOT NULL,
                        predicate TEXT NOT NULL,
                        value TEXT NOT NULL,
                        confidence REAL NOT NULL,
                        priority TEXT NOT NULL DEFAULT 'NORMAL',
                        memory_category TEXT NOT NULL DEFAULT 'SEMANTIC',
                        embedding_model TEXT,
                        embedding_dimension INTEGER,
                        embedding_vector TEXT,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL,
                        source_conversation TEXT NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS episodic_memory (
                        id TEXT PRIMARY KEY,
                        title TEXT NOT NULL,
                        description TEXT NOT NULL,
                        importance REAL NOT NULL,
                        created_at TEXT NOT NULL,
                        source_conversation TEXT NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS procedural_memory (
                        id TEXT PRIMARY KEY,
                        name TEXT NOT NULL,
                        steps TEXT NOT NULL,
                        confidence REAL NOT NULL,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL,
                        source_conversation TEXT NOT NULL
                    )
                    """);
            addColumnIfMissing(statement, "semantic_memory", "priority", "TEXT NOT NULL DEFAULT 'NORMAL'");
            addColumnIfMissing(statement, "semantic_memory", "memory_category", "TEXT NOT NULL DEFAULT 'SEMANTIC'");
            addColumnIfMissing(statement, "semantic_memory", "embedding_model", "TEXT");
            addColumnIfMissing(statement, "semantic_memory", "embedding_dimension", "INTEGER");
            addColumnIfMissing(statement, "semantic_memory", "embedding_vector", "TEXT");
            createDurableConversationTables(statement);
            migrateWorkingMemoryIntoDurableConversationTables(statement);
            LOGGER.info("[JARVIS] Cognitive Memory SQLite initialized.");
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not initialize SQLite memory tables", exception);
        }
    }

    /**
     * Creates the durable conversation tables (ETAP C) - {@code conversations} (metadata, one row
     * per conversation) and {@code conversation_messages} (full, never-trimmed message log),
     * separate from the existing, intentionally bounded {@code working_memory} table. {@code
     * conversation_summaries} is an append-only audit log of every rolling-summary regeneration
     * (planned consumer: the rolling-summary stage), distinct from {@code
     * conversations.rolling_summary}/{@code summary_until_sequence}, which always hold only the
     * current summary for fast prompt-building lookups without a join.
     */
    private void createDurableConversationTables(Statement statement) throws SQLException {
        statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS conversations (
                    id TEXT PRIMARY KEY,
                    title TEXT NOT NULL DEFAULT 'Nowa rozmowa',
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    archived INTEGER NOT NULL DEFAULT 0,
                    last_model TEXT NOT NULL DEFAULT '',
                    title_source TEXT NOT NULL DEFAULT 'DEFAULT',
                    rolling_summary TEXT NOT NULL DEFAULT '',
                    summary_until_sequence INTEGER NOT NULL DEFAULT 0
                )
                """);
        addColumnIfMissing(statement, "conversations", "title_source", "TEXT NOT NULL DEFAULT 'DEFAULT'");
        statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS conversation_messages (
                    id TEXT PRIMARY KEY,
                    conversation_id TEXT NOT NULL,
                    request_id TEXT NOT NULL DEFAULT '',
                    sequence_number INTEGER NOT NULL,
                    role TEXT NOT NULL,
                    message_type TEXT NOT NULL DEFAULT 'CHAT',
                    content TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    status TEXT NOT NULL DEFAULT 'FINAL',
                    metadata_json TEXT NOT NULL DEFAULT '{}'
                )
                """);
        statement.executeUpdate("""
                CREATE INDEX IF NOT EXISTS idx_conversation_messages_conversation
                ON conversation_messages(conversation_id, sequence_number)
                """);
        statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS conversation_summaries (
                    conversation_id TEXT NOT NULL,
                    summary TEXT NOT NULL,
                    covered_until_sequence INTEGER NOT NULL,
                    created_at TEXT NOT NULL,
                    model TEXT NOT NULL DEFAULT '',
                    source_message_count INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY (conversation_id, covered_until_sequence)
                )
                """);
    }

    /**
     * Idempotent, non-destructive migration: copies whatever is currently in {@code working_memory}
     * (which physically trims older rows) into the durable {@code conversations}/{@code
     * conversation_messages} tables, so upgrading never loses what is still there at upgrade time.
     * {@code INSERT OR IGNORE} keyed by primary key makes re-running this on every startup a cheap
     * no-op once migrated - never re-inserts, never duplicates, never deletes anything from either
     * table. Messages already trimmed out of {@code working_memory} <i>before</i> this upgrade ran
     * were already unrecoverable beforehand; this migration cannot and does not claim to recover
     * them - only what is still physically present gets copied forward. From this point on, new
     * messages are dual-written to both tables going forward (see {@code
     * SQLiteConversationMemoryService}), so no further loss occurs.
     */
    private void migrateWorkingMemoryIntoDurableConversationTables(Statement statement) throws SQLException {
        statement.executeUpdate("""
                INSERT OR IGNORE INTO conversations
                (id, title, created_at, updated_at, archived, last_model, title_source, rolling_summary, summary_until_sequence)
                SELECT conversation_id, 'Nowa rozmowa', MIN(created_at), MAX(created_at), 0, '', 'DEFAULT', '', 0
                FROM working_memory
                GROUP BY conversation_id
                """);
        statement.executeUpdate("""
                INSERT OR IGNORE INTO conversation_messages
                (id, conversation_id, request_id, sequence_number, role, message_type, content, created_at, status, metadata_json)
                SELECT id, conversation_id, request_id, sequence_number, role, message_type, content, created_at, status, '{}'
                FROM working_memory
                """);
    }

    private void addColumnIfMissing(Statement statement, String table, String column, String definition) throws SQLException {
        try {
            statement.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        } catch (SQLException exception) {
            if (!exception.getMessage().toLowerCase(java.util.Locale.ROOT).contains("duplicate column name")) {
                throw exception;
            }
        }
    }
}
