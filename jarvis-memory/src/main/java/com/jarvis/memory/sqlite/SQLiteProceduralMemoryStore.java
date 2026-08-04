package com.jarvis.memory.sqlite;

import com.jarvis.memory.cognitive.ProceduralMemoryRecord;
import com.jarvis.memory.cognitive.ProceduralMemoryStore;
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
 * SQLite-backed procedural memory store.
 */
@Repository
public class SQLiteProceduralMemoryStore implements ProceduralMemoryStore {

    private final SQLiteConnectionFactory connectionFactory;

    /**
     * Creates the procedural memory store.
     *
     * @param connectionFactory SQLite connection factory
     */
    public SQLiteProceduralMemoryStore(SQLiteConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    @Override
    public void save(ProceduralMemoryRecord record) {
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO procedural_memory
                     (id, name, steps, confidence, created_at, updated_at, source_conversation)
                     VALUES (?, ?, ?, ?, ?, ?, ?)
                     """)) {
            statement.setString(1, record.id().toString());
            statement.setString(2, record.name());
            statement.setString(3, record.steps());
            statement.setDouble(4, record.confidence());
            statement.setString(5, record.createdAt().toString());
            statement.setString(6, record.updatedAt().toString());
            statement.setString(7, record.sourceConversation());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not save procedural memory", exception);
        }
    }

    @Override
    public void update(ProceduralMemoryRecord record) {
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE procedural_memory
                     SET steps = ?, confidence = ?, updated_at = ?, source_conversation = ?
                     WHERE id = ?
                     """)) {
            statement.setString(1, record.steps());
            statement.setDouble(2, record.confidence());
            statement.setString(3, record.updatedAt().toString());
            statement.setString(4, record.sourceConversation());
            statement.setString(5, record.id().toString());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not update procedural memory", exception);
        }
    }

    @Override
    public Optional<ProceduralMemoryRecord> findByName(String name) {
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT * FROM procedural_memory
                     WHERE lower(name) = lower(?)
                     LIMIT 1
                     """)) {
            statement.setString(1, name);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return Optional.of(read(resultSet));
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not find procedural memory", exception);
        }
        return Optional.empty();
    }

    @Override
    public List<ProceduralMemoryRecord> search(String query, int limit) {
        return searchInternal(query, limit);
    }

    @Override
    public List<ProceduralMemoryRecord> listAll() {
        return searchInternal("", Integer.MAX_VALUE);
    }

    @Override
    public boolean delete(UUID id) {
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM procedural_memory WHERE id = ?")) {
            statement.setString(1, id.toString());
            return statement.executeUpdate() > 0;
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not delete procedural memory", exception);
        }
    }

    private List<ProceduralMemoryRecord> searchInternal(String query, int limit) {
        List<ProceduralMemoryRecord> records = new ArrayList<>();
        String pattern = "%" + (query == null ? "" : query.toLowerCase()) + "%";
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT * FROM procedural_memory
                     WHERE ? = '%%'
                        OR lower(name) LIKE ?
                        OR lower(steps) LIKE ?
                     ORDER BY datetime(updated_at) DESC
                     LIMIT ?
                     """)) {
            statement.setString(1, pattern);
            statement.setString(2, pattern);
            statement.setString(3, pattern);
            statement.setInt(4, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    records.add(read(resultSet));
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not search procedural memory", exception);
        }
        return records;
    }

    private ProceduralMemoryRecord read(ResultSet resultSet) throws SQLException {
        return new ProceduralMemoryRecord(
                UUID.fromString(resultSet.getString("id")),
                resultSet.getString("name"),
                resultSet.getString("steps"),
                resultSet.getDouble("confidence"),
                Instant.parse(resultSet.getString("created_at")),
                Instant.parse(resultSet.getString("updated_at")),
                resultSet.getString("source_conversation")
        );
    }
}
