package com.jarvis.memory.sqlite;

import com.jarvis.common.memory.ConversationMessage;
import com.jarvis.common.memory.ConversationMessageStatus;
import com.jarvis.common.memory.ConversationMessageType;
import com.jarvis.common.memory.MessageRole;
import com.jarvis.common.auth.CurrentUserContext;
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
 * SQLite-backed durable conversation message log - see {@link
 * com.jarvis.memory.conversation.ConversationMessageRepository}'s javadoc for why this exists
 * separately from the trimmed {@code SQLiteWorkingMemoryStore}. Reuses the message {@code id} as
 * its own primary key with {@code INSERT OR IGNORE}, so appending the same message twice (e.g. a
 * repeated migration pass) is naturally idempotent.
 */
@Repository
public class SQLiteConversationMessageRepository implements com.jarvis.memory.conversation.ConversationMessageRepository {

    private final SQLiteConnectionFactory connectionFactory;

    /**
     * Creates the repository.
     *
     * @param connectionFactory SQLite connection factory
     */
    public SQLiteConversationMessageRepository(SQLiteConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    @Override
    public void append(String conversationId, ConversationMessage message) {
        try (Connection connection = connectionFactory.openConnection()) {
            // Self-assigns exactly like SQLiteWorkingMemoryStore does, from this table's own
            // (never-trimmed) MAX(sequence_number) - since both stores are fed the same messages,
            // in the same order, from the single SQLiteConversationMemoryService#addMessage call
            // site, their independently self-assigned sequence numbers stay numerically identical
            // without either store needing to know about the other.
            long sequenceNumber = message.sequenceNumber() > 0L ? message.sequenceNumber() : nextSequence(connection, conversationId);
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT OR IGNORE INTO conversation_messages
                    (id, conversation_id, user_id, request_id, sequence_number, role, message_type, content, created_at, status, metadata_json)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '{}')
                    """)) {
                insert.setString(1, message.id().toString());
                insert.setString(2, conversationId);
                insert.setString(3, userId());
                insert.setString(4, message.requestId());
                insert.setLong(5, sequenceNumber);
                insert.setString(6, message.role().name());
                insert.setString(7, message.messageType().name());
                insert.setString(8, message.content());
                insert.setString(9, message.createdAt().toString());
                insert.setString(10, message.status().name());
                insert.executeUpdate();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not append durable conversation message", exception);
        }
    }

    private long nextSequence(Connection connection, String conversationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COALESCE(MAX(sequence_number), 0) + 1
                FROM conversation_messages
                WHERE conversation_id = ? AND user_id = ?
                """)) {
            statement.setString(1, conversationId);
            statement.setString(2, userId());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getLong(1) : 1L;
            }
        }
    }

    @Override
    public List<ConversationMessage> getAllMessages(String conversationId) {
        List<ConversationMessage> messages = new ArrayList<>();
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT id, conversation_id, request_id, role, content, created_at, sequence_number, message_type, status
                     FROM conversation_messages
                     WHERE conversation_id = ? AND user_id = ?
                     ORDER BY sequence_number ASC, datetime(created_at) ASC
                     """)) {
            statement.setString(1, conversationId);
            statement.setString(2, userId());
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
            throw new IllegalStateException("Could not read durable conversation messages", exception);
        }
        return messages;
    }

    @Override
    public int countMessages(String conversationId) {
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM conversation_messages WHERE conversation_id = ? AND user_id = ?")) {
            statement.setString(1, conversationId);
            statement.setString(2, userId());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getInt(1) : 0;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not count durable conversation messages", exception);
        }
    }

    @Override
    public int deleteAll(String conversationId) {
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM conversation_messages WHERE conversation_id = ? AND user_id = ?")) {
            statement.setString(1, conversationId);
            statement.setString(2, userId());
            return statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not delete durable conversation messages", exception);
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
