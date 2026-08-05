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
                        role TEXT NOT NULL,
                        content TEXT NOT NULL,
                        created_at TEXT NOT NULL
                    )
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
            LOGGER.info("[JARVIS] Cognitive Memory SQLite initialized.");
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not initialize SQLite memory tables", exception);
        }
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
