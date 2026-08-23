package com.jarvis.memory.sqlite;

import com.jarvis.common.memory.ConversationMessage;
import com.jarvis.common.memory.ConversationMessageStatus;
import com.jarvis.common.memory.ConversationMessageType;
import com.jarvis.common.memory.MessageRole;
import com.jarvis.common.auth.CurrentUserContext;
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
                     INSERT OR IGNORE INTO working_memory
                     (id, conversation_id, user_id, request_id, role, content, created_at, sequence_number, message_type, status)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                     """)) {
            long sequenceNumber = message.sequenceNumber() > 0L ? message.sequenceNumber() : nextSequence(connection, conversationId);
            insert.setString(1, message.id().toString());
            insert.setString(2, conversationId);
            insert.setString(3, userId());
            insert.setString(4, message.requestId());
            insert.setString(5, message.role().name());
            insert.setString(6, message.content());
            insert.setString(7, message.createdAt().toString());
            insert.setLong(8, sequenceNumber);
            insert.setString(9, message.messageType().name());
            insert.setString(10, message.status().name());
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
                     SELECT id, conversation_id, request_id, role, content, created_at, sequence_number, message_type, status
                     FROM working_memory
                     WHERE conversation_id = ?
                       AND user_id = ?
                       AND status = 'FINAL'
                     ORDER BY sequence_number DESC, datetime(created_at) DESC
                     LIMIT ?
                     """)) {
            statement.setString(1, conversationId);
            statement.setString(2, userId());
            statement.setInt(3, properties.workingHistoryLength());
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    messages.add(new ConversationMessage(
                            UUID.fromString(resultSet.getString("id")),
                            resultSet.getString("conversation_id"),
                            resultSet.getString("request_id"),
                            MessageRole.valueOf(resultSet.getString("role")),
                            resultSet.getString("content"),
                            Instant.parse(resultSet.getString("created_at")),
                            resultSet.getLong("sequence_number"),
                            parseType(resultSet.getString("message_type")),
                            parseStatus(resultSet.getString("status"))
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
                  AND user_id = ?
                  AND id NOT IN (
                    SELECT id FROM working_memory
                    WHERE conversation_id = ?
                    AND user_id = ?
                    ORDER BY sequence_number DESC, datetime(created_at) DESC
                    LIMIT ?
                  )
                """)) {
            statement.setString(1, conversationId);
            statement.setString(2, userId());
            statement.setString(3, conversationId);
            statement.setString(4, userId());
            statement.setInt(5, properties.workingHistoryLength());
            statement.executeUpdate();
        }
    }

    @Override
    public int deleteConversation(String conversationId) {
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM working_memory WHERE conversation_id = ? AND user_id = ?")) {
            statement.setString(1, conversationId);
            statement.setString(2, userId());
            return statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not delete conversation history", exception);
        }
    }

    @Override
    public int countMessages(String conversationId) {
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM working_memory WHERE conversation_id = ? AND user_id = ?")) {
            statement.setString(1, conversationId);
            statement.setString(2, userId());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getInt(1) : 0;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not count conversation history", exception);
        }
    }

    private long nextSequence(Connection connection, String conversationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COALESCE(MAX(sequence_number), 0) + 1
                FROM working_memory
                WHERE conversation_id = ? AND user_id = ?
                """)) {
            statement.setString(1, conversationId);
            statement.setString(2, userId());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getLong(1) : 1L;
            }
        }
    }

    private ConversationMessageType parseType(String value) {
        try {
            return value == null ? ConversationMessageType.CHAT : ConversationMessageType.valueOf(value);
        } catch (IllegalArgumentException exception) {
            return ConversationMessageType.CHAT;
        }
    }

    private ConversationMessageStatus parseStatus(String value) {
        try {
            return value == null ? ConversationMessageStatus.FINAL : ConversationMessageStatus.valueOf(value);
        } catch (IllegalArgumentException exception) {
            return ConversationMessageStatus.FINAL;
        }
    }

    private String userId() {
        return CurrentUserContext.requiredUserId();
    }
}
