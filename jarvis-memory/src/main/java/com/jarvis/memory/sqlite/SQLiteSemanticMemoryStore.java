package com.jarvis.memory.sqlite;

import com.jarvis.memory.cognitive.SemanticMemoryRecord;
import com.jarvis.memory.cognitive.SemanticMemoryStore;
import com.jarvis.common.memory.MemoryCategory;
import com.jarvis.common.memory.MemoryPriority;
import org.springframework.stereotype.Repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SQLite-backed semantic memory store.
 */
@Repository
public class SQLiteSemanticMemoryStore implements SemanticMemoryStore {

    private final SQLiteConnectionFactory connectionFactory;

    /**
     * Creates the semantic memory store.
     *
     * @param connectionFactory SQLite connection factory
     */
    public SQLiteSemanticMemoryStore(SQLiteConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    @Override
    public void save(SemanticMemoryRecord record) {
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO semantic_memory
                     (id, subject, predicate, value, confidence, priority, memory_category, created_at, updated_at, source_conversation)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                     """)) {
            write(statement, record);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not save semantic memory", exception);
        }
    }

    @Override
    public void update(SemanticMemoryRecord record) {
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE semantic_memory
                     SET value = ?, confidence = ?, priority = ?, memory_category = ?, updated_at = ?, source_conversation = ?
                     WHERE id = ?
                     """)) {
            statement.setString(1, record.value());
            statement.setDouble(2, record.confidence());
            statement.setString(3, record.priority().name());
            statement.setString(4, record.category().name());
            statement.setString(5, record.updatedAt().toString());
            statement.setString(6, record.sourceConversation());
            statement.setString(7, record.id().toString());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not update semantic memory", exception);
        }
    }

    @Override
    public Optional<SemanticMemoryRecord> findExact(String subject, String predicate, String value) {
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT * FROM semantic_memory
                     WHERE lower(subject) = lower(?) AND lower(predicate) = lower(?) AND lower(value) = lower(?)
                     LIMIT 1
                     """)) {
            statement.setString(1, subject);
            statement.setString(2, predicate);
            statement.setString(3, value);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return Optional.of(read(resultSet));
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not find semantic memory", exception);
        }
        return Optional.empty();
    }

    @Override
    public Optional<SemanticMemoryRecord> findById(UUID id) {
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT * FROM semantic_memory WHERE id = ? LIMIT 1")) {
            statement.setString(1, id.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return Optional.of(read(resultSet));
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not find semantic memory by id", exception);
        }
        return Optional.empty();
    }

    @Override
    public List<SemanticMemoryRecord> search(String query, int limit) {
        return searchInternal(query, limit);
    }

    @Override
    public List<SemanticMemoryRecord> listAll() {
        return searchInternal("", Integer.MAX_VALUE);
    }

    @Override
    public boolean delete(UUID id) {
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM semantic_memory WHERE id = ?")) {
            statement.setString(1, id.toString());
            return statement.executeUpdate() > 0;
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not delete semantic memory", exception);
        }
    }

    private List<SemanticMemoryRecord> searchInternal(String query, int limit) {
        List<SemanticMemoryRecord> records = new ArrayList<>();
        String pattern = "%" + (query == null ? "" : query.toLowerCase()) + "%";
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT * FROM semantic_memory
                     WHERE ? = '%%'
                        OR lower(subject) LIKE ?
                        OR lower(predicate) LIKE ?
                        OR lower(value) LIKE ?
                     ORDER BY datetime(updated_at) DESC
                     LIMIT ?
                     """)) {
            statement.setString(1, pattern);
            statement.setString(2, pattern);
            statement.setString(3, pattern);
            statement.setString(4, pattern);
            statement.setInt(5, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    records.add(read(resultSet));
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not search semantic memory", exception);
        }
        return records;
    }

    private void write(PreparedStatement statement, SemanticMemoryRecord record) throws SQLException {
        statement.setString(1, record.id().toString());
        statement.setString(2, record.subject());
        statement.setString(3, record.predicate());
        statement.setString(4, record.value());
        statement.setDouble(5, record.confidence());
        statement.setString(6, record.priority().name());
        statement.setString(7, record.category().name());
        statement.setString(8, record.createdAt().toString());
        statement.setString(9, record.updatedAt().toString());
        statement.setString(10, record.sourceConversation());
    }

    private SemanticMemoryRecord read(ResultSet resultSet) throws SQLException {
        return new SemanticMemoryRecord(
                UUID.fromString(resultSet.getString("id")),
                resultSet.getString("subject"),
                resultSet.getString("predicate"),
                resultSet.getString("value"),
                resultSet.getDouble("confidence"),
                parsePriority(resultSet.getString("priority")),
                parseCategory(resultSet.getString("memory_category")),
                Instant.parse(resultSet.getString("created_at")),
                Instant.parse(resultSet.getString("updated_at")),
                resultSet.getString("source_conversation")
        );
    }

    private MemoryPriority parsePriority(String value) {
        try {
            return value == null ? MemoryPriority.NORMAL : MemoryPriority.valueOf(value);
        } catch (IllegalArgumentException exception) {
            return MemoryPriority.NORMAL;
        }
    }

    private MemoryCategory parseCategory(String value) {
        try {
            return value == null ? MemoryCategory.SEMANTIC : MemoryCategory.valueOf(value);
        } catch (IllegalArgumentException exception) {
            return MemoryCategory.SEMANTIC;
        }
    }
}
