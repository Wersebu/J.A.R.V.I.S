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
                        user_id TEXT NOT NULL DEFAULT 'local-user',
                        request_id TEXT NOT NULL DEFAULT '',
                        role TEXT NOT NULL,
                        content TEXT NOT NULL,
                        created_at TEXT NOT NULL,
                        sequence_number INTEGER NOT NULL DEFAULT 0,
                        message_type TEXT NOT NULL DEFAULT 'CHAT',
                        status TEXT NOT NULL DEFAULT 'FINAL'
                    )
                    """);
            addColumnIfMissing(statement, "working_memory", "user_id", "TEXT NOT NULL DEFAULT 'local-user'");
            addColumnIfMissing(statement, "working_memory", "request_id", "TEXT NOT NULL DEFAULT ''");
            addColumnIfMissing(statement, "working_memory", "sequence_number", "INTEGER NOT NULL DEFAULT 0");
            addColumnIfMissing(statement, "working_memory", "message_type", "TEXT NOT NULL DEFAULT 'CHAT'");
            addColumnIfMissing(statement, "working_memory", "status", "TEXT NOT NULL DEFAULT 'FINAL'");
            statement.executeUpdate("""
                    CREATE UNIQUE INDEX IF NOT EXISTS idx_working_memory_request_role_type
                    ON working_memory(user_id, request_id, role, message_type)
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
            createAuthTables(statement);
            createDurableConversationTables(statement);
            createCodingAgentTables(statement);
            migrateWorkingMemoryIntoDurableConversationTables(statement);
            createConversationImageTable(statement);
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
                    user_id TEXT NOT NULL DEFAULT 'local-user',
                    folder_id TEXT NOT NULL DEFAULT '',
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
        addColumnIfMissing(statement, "conversations", "user_id", "TEXT NOT NULL DEFAULT 'local-user'");
        addColumnIfMissing(statement, "conversations", "folder_id", "TEXT NOT NULL DEFAULT ''");
        addColumnIfMissing(statement, "conversations", "title_source", "TEXT NOT NULL DEFAULT 'DEFAULT'");
        statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS conversation_messages (
                    id TEXT PRIMARY KEY,
                    conversation_id TEXT NOT NULL,
                    user_id TEXT NOT NULL DEFAULT 'local-user',
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
        addColumnIfMissing(statement, "conversation_messages", "user_id", "TEXT NOT NULL DEFAULT 'local-user'");
        statement.executeUpdate("""
                CREATE INDEX IF NOT EXISTS idx_conversation_messages_conversation
                ON conversation_messages(user_id, conversation_id, sequence_number)
                """);
        statement.executeUpdate("""
                CREATE INDEX IF NOT EXISTS idx_conversations_user_updated
                ON conversations(user_id, updated_at)
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

    private void createAuthTables(Statement statement) throws SQLException {
        statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS users (
                    id TEXT PRIMARY KEY,
                    email TEXT NOT NULL UNIQUE,
                    password_hash TEXT NOT NULL,
                    display_name TEXT NOT NULL DEFAULT '',
                    role TEXT NOT NULL DEFAULT 'USER',
                    active INTEGER NOT NULL DEFAULT 1,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    last_login_at TEXT NOT NULL DEFAULT ''
                )
                """);
        statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS user_sessions (
                    id TEXT PRIMARY KEY,
                    user_id TEXT NOT NULL,
                    token_hash TEXT NOT NULL UNIQUE,
                    created_at TEXT NOT NULL,
                    expires_at TEXT NOT NULL,
                    revoked_at TEXT NOT NULL DEFAULT ''
                )
                """);
        statement.executeUpdate("""
                CREATE INDEX IF NOT EXISTS idx_user_sessions_token
                ON user_sessions(token_hash, expires_at)
                """);
        statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS user_settings (
                    user_id TEXT PRIMARY KEY,
                    global_prompt TEXT NOT NULL DEFAULT '',
                    updated_at TEXT NOT NULL
                )
                """);
        statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS conversation_folders (
                    id TEXT PRIMARY KEY,
                    user_id TEXT NOT NULL,
                    name TEXT NOT NULL,
                    system_prompt TEXT NOT NULL DEFAULT '',
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )
                """);
        statement.executeUpdate("""
                CREATE INDEX IF NOT EXISTS idx_conversation_folders_user
                ON conversation_folders(user_id, lower(name))
                """);
    }

    private void createCodingAgentTables(Statement statement) throws SQLException {
        statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS coding_workspaces (
                    id TEXT PRIMARY KEY,
                    owner_user_id TEXT NOT NULL DEFAULT 'local-user',
                    name TEXT NOT NULL DEFAULT '',
                    windows_path TEXT NOT NULL,
                    host TEXT NOT NULL,
                    project_type TEXT NOT NULL DEFAULT '',
                    detected_build_systems_json TEXT NOT NULL DEFAULT '[]',
                    git_repository INTEGER NOT NULL DEFAULT 0,
                    git_branch TEXT NOT NULL DEFAULT '',
                    git_head_commit TEXT NOT NULL DEFAULT '',
                    git_status TEXT NOT NULL DEFAULT '',
                    autonomy_level TEXT NOT NULL DEFAULT 'EDIT_AND_TEST',
                    build_command TEXT NOT NULL DEFAULT '',
                    test_command TEXT NOT NULL DEFAULT '',
                    last_used_at TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )
                """);
        addColumnIfMissing(statement, "coding_workspaces", "owner_user_id", "TEXT NOT NULL DEFAULT 'local-user'");
        addColumnIfMissing(statement, "coding_workspaces", "created_at", "TEXT NOT NULL DEFAULT '1970-01-01T00:00:00Z'");
        addColumnIfMissing(statement, "coding_workspaces", "updated_at", "TEXT NOT NULL DEFAULT '1970-01-01T00:00:00Z'");
        statement.executeUpdate("""
                CREATE INDEX IF NOT EXISTS idx_coding_workspaces_owner_used
                ON coding_workspaces(owner_user_id, last_used_at)
                """);

        statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS coding_tasks (
                    id TEXT PRIMARY KEY,
                    workspace_id TEXT NOT NULL,
                    owner_user_id TEXT NOT NULL DEFAULT 'local-user',
                    conversation_id TEXT NOT NULL DEFAULT '',
                    model TEXT NOT NULL DEFAULT '',
                    prompt TEXT NOT NULL DEFAULT '',
                    status TEXT NOT NULL,
                    plan_json TEXT NOT NULL DEFAULT '[]',
                    current_action TEXT NOT NULL DEFAULT '',
                    iteration INTEGER NOT NULL DEFAULT 0,
                    started_at TEXT NOT NULL,
                    finished_at TEXT NOT NULL DEFAULT '',
                    changed_files_json TEXT NOT NULL DEFAULT '{}',
                    build_result TEXT NOT NULL DEFAULT '',
                    test_result TEXT NOT NULL DEFAULT '',
                    failure_reason TEXT NOT NULL DEFAULT '',
                    updated_at TEXT NOT NULL,
                    final_answer TEXT NOT NULL DEFAULT '',
                    system_prompt_version TEXT NOT NULL DEFAULT '',
                    opencode_session_id TEXT NOT NULL DEFAULT '',
                    initial_git_snapshot_json TEXT NOT NULL DEFAULT '{}',
                    final_git_snapshot_json TEXT NOT NULL DEFAULT '{}'
                )
                """);
        addColumnIfMissing(statement, "coding_tasks", "owner_user_id", "TEXT NOT NULL DEFAULT 'local-user'");
        addColumnIfMissing(statement, "coding_tasks", "updated_at", "TEXT NOT NULL DEFAULT '1970-01-01T00:00:00Z'");
        addColumnIfMissing(statement, "coding_tasks", "final_answer", "TEXT NOT NULL DEFAULT ''");
        addColumnIfMissing(statement, "coding_tasks", "system_prompt_version", "TEXT NOT NULL DEFAULT ''");
        addColumnIfMissing(statement, "coding_tasks", "opencode_session_id", "TEXT NOT NULL DEFAULT ''");
        addColumnIfMissing(statement, "coding_tasks", "initial_git_snapshot_json", "TEXT NOT NULL DEFAULT '{}'");
        addColumnIfMissing(statement, "coding_tasks", "final_git_snapshot_json", "TEXT NOT NULL DEFAULT '{}'");
        statement.executeUpdate("""
                CREATE INDEX IF NOT EXISTS idx_coding_tasks_owner_started
                ON coding_tasks(owner_user_id, started_at)
                """);
        statement.executeUpdate("""
                CREATE INDEX IF NOT EXISTS idx_coding_tasks_workspace
                ON coding_tasks(owner_user_id, workspace_id, started_at)
                """);

        statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS coding_approvals (
                    id TEXT PRIMARY KEY,
                    task_id TEXT NOT NULL,
                    owner_user_id TEXT NOT NULL,
                    operation TEXT NOT NULL,
                    description TEXT NOT NULL DEFAULT '',
                    risk_level TEXT NOT NULL DEFAULT '',
                    arguments_digest TEXT NOT NULL,
                    status TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    expires_at TEXT NOT NULL,
                    decided_at TEXT NOT NULL DEFAULT '',
                    consumed_at TEXT NOT NULL DEFAULT ''
                )
                """);
        addColumnIfMissing(statement, "coding_approvals", "owner_user_id", "TEXT NOT NULL DEFAULT 'local-user'");
        addColumnIfMissing(statement, "coding_approvals", "description", "TEXT NOT NULL DEFAULT ''");
        addColumnIfMissing(statement, "coding_approvals", "risk_level", "TEXT NOT NULL DEFAULT ''");
        addColumnIfMissing(statement, "coding_approvals", "arguments_digest", "TEXT NOT NULL DEFAULT ''");
        addColumnIfMissing(statement, "coding_approvals", "decided_at", "TEXT NOT NULL DEFAULT ''");
        addColumnIfMissing(statement, "coding_approvals", "consumed_at", "TEXT NOT NULL DEFAULT ''");
        statement.executeUpdate("""
                CREATE INDEX IF NOT EXISTS idx_coding_approvals_owner_task
                ON coding_approvals(owner_user_id, task_id, status, created_at)
                """);
        statement.executeUpdate("""
                CREATE UNIQUE INDEX IF NOT EXISTS idx_coding_approvals_pending_digest
                ON coding_approvals(task_id, operation, arguments_digest)
                WHERE status = 'PENDING'
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
                (id, user_id, folder_id, title, created_at, updated_at, archived, last_model, title_source, rolling_summary, summary_until_sequence)
                SELECT conversation_id, 'local-user', '', 'Nowa rozmowa', MIN(created_at), MAX(created_at), 0, '', 'DEFAULT', '', 0
                FROM working_memory
                GROUP BY conversation_id
                """);
        statement.executeUpdate("""
                INSERT OR IGNORE INTO conversation_messages
                (id, conversation_id, user_id, request_id, sequence_number, role, message_type, content, created_at, status, metadata_json)
                SELECT id, conversation_id, 'local-user', request_id, sequence_number, role, message_type, content, created_at, status, '{}'
                FROM working_memory
                """);
    }

    /**
     * Creates the conversation-scoped image registry table (metadata/references only - see {@code
     * ConversationImageRecord}'s javadoc for why no image bytes/base64 ever land here). {@code
     * (conversation_id, attachment_id)} is unique so registering the same attachment twice for the
     * same conversation is naturally idempotent via {@code INSERT OR IGNORE}.
     */
    private void createConversationImageTable(Statement statement) throws SQLException {
        statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS conversation_images (
                    id TEXT PRIMARY KEY,
                    conversation_id TEXT NOT NULL,
                    message_id TEXT NOT NULL DEFAULT '',
                    source_message_ordinal INTEGER NOT NULL DEFAULT 1,
                    ordinal_in_message INTEGER NOT NULL DEFAULT 1,
                    conversation_label TEXT NOT NULL DEFAULT '',
                    attachment_id TEXT NOT NULL,
                    workspace_id TEXT NOT NULL,
                    original_file_name TEXT NOT NULL DEFAULT '',
                    media_type TEXT NOT NULL DEFAULT '',
                    size_bytes INTEGER NOT NULL DEFAULT 0,
                    created_at TEXT NOT NULL,
                    expires_at TEXT NOT NULL,
                    status TEXT NOT NULL DEFAULT 'AVAILABLE'
                )
                """);
        statement.executeUpdate("""
                CREATE UNIQUE INDEX IF NOT EXISTS idx_conversation_images_identity
                ON conversation_images(conversation_id, attachment_id)
                """);
        statement.executeUpdate("""
                CREATE INDEX IF NOT EXISTS idx_conversation_images_conversation
                ON conversation_images(conversation_id, source_message_ordinal, ordinal_in_message)
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
