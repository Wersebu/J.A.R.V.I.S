package com.jarvis.memory.sqlite;

import com.jarvis.common.memory.ConversationMessage;
import com.jarvis.common.memory.MessageRole;
import com.jarvis.memory.cognitive.MemoryProperties;
import com.jarvis.memory.cognitive.WorkingMemoryStore;
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
 * SQLite-backed working memory store.
 */
@Repository
public class SQLiteWorkingMemoryStore implements WorkingMemoryStore {

    private final SQLiteConnectionFactory connectionFactory;
    private final MemoryProperties properties;

    /**
     * Creates the working memory store.
     *
     * @param connectionFactory SQLite connection factory
     * @param properties memory properties
     */
    public SQLiteWorkingMemoryStore(SQLiteConnectionFactory connectionFactory, MemoryProperties properties) {
        this.connectionFactory = connectionFactory;
        this.properties = properties;
    }

    @Override
    public void addMessage(String conversationId, ConversationMessage message) {
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement insert = connection.prepareStatement("""
                     INSERT INTO working_memory (id, conversation_id, role, content, created_at)
                     VALUES (?, ?, ?, ?, ?)
                     """)) {
            insert.setString(1, UUID.randomUUID().toString());
            insert.setString(2, conversationId);
            insert.setString(3, message.role().name());
            insert.setString(4, message.content());
            insert.setString(5, message.createdAt().toString());
            insert.executeUpdate();
            trimConversation(connection, conversationId);
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not add working memory message", exception);
        }
    }

    @Override
    public List<ConversationMessage> getRecentMessages(String conversationId) {
        List<ConversationMessage> messages = new ArrayList<>();
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT role, content, created_at
                     FROM working_memory
                     WHERE conversation_id = ?
                     ORDER BY datetime(created_at) DESC
                     LIMIT ?
                     """)) {
            statement.setString(1, conversationId);
            statement.setInt(2, properties.workingHistoryLength());
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    messages.add(new ConversationMessage(
                            MessageRole.valueOf(resultSet.getString("role")),
                            resultSet.getString("content"),
                            Instant.parse(resultSet.getString("created_at"))
                    ));
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not read working memory", exception);
        }
        return messages.reversed();
    }

    private void trimConversation(Connection connection, String conversationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                DELETE FROM working_memory
                WHERE conversation_id = ?
                  AND id NOT IN (
                    SELECT id FROM working_memory
                    WHERE conversation_id = ?
                    ORDER BY datetime(created_at) DESC
                    LIMIT ?
                  )
                """)) {
            statement.setString(1, conversationId);
            statement.setString(2, conversationId);
            statement.setInt(3, properties.workingHistoryLength());
            statement.executeUpdate();
        }
    }
}
