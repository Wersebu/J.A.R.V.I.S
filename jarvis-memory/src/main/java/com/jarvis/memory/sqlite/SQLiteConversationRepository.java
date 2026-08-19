package com.jarvis.memory.sqlite;

import com.jarvis.memory.conversation.ConversationRecord;
import com.jarvis.memory.conversation.ConversationRepository;
import org.springframework.stereotype.Repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * SQLite-backed durable conversation metadata store.
 */
@Repository
public class SQLiteConversationRepository implements ConversationRepository {

    private final SQLiteConnectionFactory connectionFactory;

    /**
     * Creates the repository.
     *
     * @param connectionFactory SQLite connection factory
     */
    public SQLiteConversationRepository(SQLiteConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    @Override
    public ConversationRecord createIfAbsent(String conversationId, Instant now) {
        Optional<ConversationRecord> existing = find(conversationId);
        if (existing.isPresent()) {
            return existing.get();
        }
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement insert = connection.prepareStatement("""
                     INSERT OR IGNORE INTO conversations
                     (id, title, created_at, updated_at, archived, last_model, rolling_summary, summary_until_sequence)
                     VALUES (?, ?, ?, ?, 0, '', '', 0)
                     """)) {
            insert.setString(1, conversationId);
            insert.setString(2, ConversationRecord.DEFAULT_TITLE);
            insert.setString(3, now.toString());
            insert.setString(4, now.toString());
            insert.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not create conversation record", exception);
        }
        // A concurrent createIfAbsent for the same id may have won the race - INSERT OR IGNORE
        // makes that safe, so re-reading afterward always returns the real, single row either way.
        return find(conversationId).orElseThrow(() -> new IllegalStateException("Conversation record missing after insert: " + conversationId));
    }

    @Override
    public Optional<ConversationRecord> find(String conversationId) {
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT id, title, created_at, updated_at, archived, last_model, rolling_summary, summary_until_sequence
                     FROM conversations WHERE id = ?
                     """)) {
            statement.setString(1, conversationId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not read conversation record", exception);
        }
    }

    @Override
    public List<ConversationRecord> list() {
        List<ConversationRecord> records = new ArrayList<>();
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT id, title, created_at, updated_at, archived, last_model, rolling_summary, summary_until_sequence
                     FROM conversations ORDER BY datetime(updated_at) DESC
                     """);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                records.add(map(resultSet));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not list conversation records", exception);
        }
        return records;
    }

    @Override
    public void touch(String conversationId, Instant now) {
        execute("UPDATE conversations SET updated_at = ? WHERE id = ?", now.toString(), conversationId);
    }

    @Override
    public void rename(String conversationId, String title) {
        execute("UPDATE conversations SET title = ? WHERE id = ?", title, conversationId);
    }

    @Override
    public void setArchived(String conversationId, boolean archived) {
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement("UPDATE conversations SET archived = ? WHERE id = ?")) {
            statement.setInt(1, archived ? 1 : 0);
            statement.setString(2, conversationId);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not update conversation archived state", exception);
        }
    }

    @Override
    public void updateLastModel(String conversationId, String model) {
        execute("UPDATE conversations SET last_model = ? WHERE id = ?", model, conversationId);
    }

    @Override
    public void updateRollingSummary(String conversationId, String summary, long coveredUntilSequence) {
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE conversations SET rolling_summary = ?, summary_until_sequence = ? WHERE id = ?")) {
            statement.setString(1, summary);
            statement.setLong(2, coveredUntilSequence);
            statement.setString(3, conversationId);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not update conversation rolling summary", exception);
        }
    }

    @Override
    public int delete(String conversationId) {
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM conversations WHERE id = ?")) {
            statement.setString(1, conversationId);
            return statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not delete conversation record", exception);
        }
    }

    private void execute(String sql, String value, String conversationId) {
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, value);
            statement.setString(2, conversationId);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not update conversation record", exception);
        }
    }

    private ConversationRecord map(ResultSet resultSet) throws SQLException {
        return new ConversationRecord(
                resultSet.getString("id"),
                resultSet.getString("title"),
                Instant.parse(resultSet.getString("created_at")),
                Instant.parse(resultSet.getString("updated_at")),
                resultSet.getInt("archived") != 0,
                resultSet.getString("last_model"),
                resultSet.getString("rolling_summary"),
                resultSet.getLong("summary_until_sequence")
        );
    }
}
