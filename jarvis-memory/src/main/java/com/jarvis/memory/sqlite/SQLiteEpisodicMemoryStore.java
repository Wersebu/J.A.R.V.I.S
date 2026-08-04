package com.jarvis.memory.sqlite;

import com.jarvis.memory.cognitive.EpisodicMemoryRecord;
import com.jarvis.memory.cognitive.EpisodicMemoryStore;
import org.springframework.stereotype.Repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * SQLite-backed episodic memory store.
 */
@Repository
public class SQLiteEpisodicMemoryStore implements EpisodicMemoryStore {

    private final SQLiteConnectionFactory connectionFactory;

    /**
     * Creates the episodic memory store.
     *
     * @param connectionFactory SQLite connection factory
     */
    public SQLiteEpisodicMemoryStore(SQLiteConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    @Override
    public void save(EpisodicMemoryRecord record) {
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO episodic_memory
                     (id, title, description, importance, created_at, source_conversation)
                     VALUES (?, ?, ?, ?, ?, ?)
                     """)) {
            statement.setString(1, record.id().toString());
            statement.setString(2, record.title());
            statement.setString(3, record.description());
            statement.setDouble(4, record.importance());
            statement.setString(5, record.createdAt().toString());
            statement.setString(6, record.sourceConversation());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not save episodic memory", exception);
        }
    }

    @Override
    public List<EpisodicMemoryRecord> search(String query, int limit) {
        return searchInternal(query, limit);
    }

    @Override
    public List<EpisodicMemoryRecord> listAll() {
        return searchInternal("", Integer.MAX_VALUE);
    }

    @Override
    public boolean delete(UUID id) {
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM episodic_memory WHERE id = ?")) {
            statement.setString(1, id.toString());
            return statement.executeUpdate() > 0;
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not delete episodic memory", exception);
        }
    }

    private List<EpisodicMemoryRecord> searchInternal(String query, int limit) {
        List<EpisodicMemoryRecord> records = new ArrayList<>();
        String pattern = "%" + (query == null ? "" : query.toLowerCase()) + "%";
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT * FROM episodic_memory
                     WHERE ? = '%%'
                        OR lower(title) LIKE ?
                        OR lower(description) LIKE ?
                     ORDER BY datetime(created_at) DESC
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
            throw new IllegalStateException("Could not search episodic memory", exception);
        }
        return records;
    }

    private EpisodicMemoryRecord read(ResultSet resultSet) throws SQLException {
        return new EpisodicMemoryRecord(
                UUID.fromString(resultSet.getString("id")),
                resultSet.getString("title"),
                resultSet.getString("description"),
                resultSet.getDouble("importance"),
                Instant.parse(resultSet.getString("created_at")),
                resultSet.getString("source_conversation")
        );
    }
}
