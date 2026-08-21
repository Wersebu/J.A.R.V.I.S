package com.jarvis.memory.image;

import com.jarvis.common.dto.AttachmentMetadata;
import com.jarvis.common.image.ConversationImageRecord;
import com.jarvis.common.image.ConversationImageRegistry;
import com.jarvis.common.image.ConversationImageStatus;
import com.jarvis.memory.sqlite.SQLiteConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * SQLite-backed durable registry of conversation-scoped image metadata - never image bytes/base64
 * (see {@link ConversationImageRecord}'s javadoc). Survives a Core restart exactly like the rest of
 * durable conversation history, but a stored record is only ever a claim that an image existed -
 * every caller re-verifies the backing file before trusting {@code AVAILABLE}.
 */
@Repository
public class SQLiteConversationImageRegistry implements ConversationImageRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(SQLiteConversationImageRegistry.class);

    private final SQLiteConnectionFactory connectionFactory;
    private final ConversationImageProperties properties;

    /**
     * Creates the registry.
     *
     * @param connectionFactory SQLite connection factory
     * @param properties conversation image configuration
     */
    public SQLiteConversationImageRegistry(SQLiteConnectionFactory connectionFactory, ConversationImageProperties properties) {
        this.connectionFactory = connectionFactory;
        this.properties = properties;
    }

    @Override
    public List<ConversationImageRecord> registerImages(String conversationId, String messageId, List<AttachmentMetadata> images) {
        if (images == null || images.isEmpty() || conversationId == null || conversationId.isBlank()) {
            return List.of();
        }
        List<ConversationImageRecord> result = new ArrayList<>();
        try (Connection connection = connectionFactory.openConnection()) {
            int sourceMessageOrdinal = resolveSourceMessageOrdinal(connection, conversationId, messageId);
            int nextLabelNumber = countRegistered(connection, conversationId) + 1;
            int ordinalInMessage = 1;
            for (AttachmentMetadata image : images) {
                Optional<ConversationImageRecord> existing = findByAttachmentId(connection, conversationId, image.attachmentId());
                if (existing.isPresent()) {
                    result.add(existing.get());
                    ordinalInMessage++;
                    continue;
                }
                Instant createdAt = image.createdAt() == null ? Instant.now() : image.createdAt();
                Instant expiresAt = createdAt.plus(properties.retention());
                ConversationImageRecord record = new ConversationImageRecord(
                        UUID.randomUUID().toString(), conversationId, messageId == null ? "" : messageId,
                        sourceMessageOrdinal, ordinalInMessage, "image-" + nextLabelNumber,
                        image.attachmentId(), image.workspaceId(), image.originalFileName(),
                        image.extension(), image.size(), createdAt, expiresAt, ConversationImageStatus.AVAILABLE);
                insert(connection, record);
                result.add(record);
                nextLabelNumber++;
                ordinalInMessage++;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not register conversation images", exception);
        }
        LOGGER.info("[CONVERSATION_IMAGES] requestId={} conversationId={} registered={} messageId={}",
                messageId, conversationId, result.size(), messageId);
        return result;
    }

    @Override
    public List<ConversationImageRecord> findForConversation(String conversationId) {
        List<ConversationImageRecord> records = new ArrayList<>();
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT * FROM conversation_images
                     WHERE conversation_id = ? AND status <> 'DELETED'
                     ORDER BY source_message_ordinal ASC, ordinal_in_message ASC
                     """)) {
            statement.setString(1, conversationId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    records.add(mapRow(resultSet));
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not read conversation images", exception);
        }
        return records;
    }

    @Override
    public Optional<ConversationImageRecord> findByAttachmentId(String conversationId, String attachmentId) {
        try (Connection connection = connectionFactory.openConnection()) {
            return findByAttachmentId(connection, conversationId, attachmentId);
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not look up conversation image", exception);
        }
    }

    @Override
    public void updateStatus(String conversationId, String attachmentId, ConversationImageStatus status) {
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE conversation_images SET status = ?
                     WHERE conversation_id = ? AND attachment_id = ?
                     """)) {
            statement.setString(1, status.name());
            statement.setString(2, conversationId);
            statement.setString(3, attachmentId);
            int updated = statement.executeUpdate();
            if (updated > 0) {
                LOGGER.info("[CONVERSATION_IMAGES] conversationId={} attachmentId={} statusUpdated={}",
                        conversationId, attachmentId, status);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not update conversation image status", exception);
        }
    }

    @Override
    public int expireOlderThan(Instant now) {
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE conversation_images SET status = 'EXPIRED'
                     WHERE status = 'AVAILABLE' AND expires_at < ?
                     """)) {
            statement.setString(1, now.toString());
            int updated = statement.executeUpdate();
            if (updated > 0) {
                LOGGER.info("[CONVERSATION_IMAGES] sweepExpired count={}", updated);
            }
            return updated;
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not expire conversation images", exception);
        }
    }

    @Override
    public int deleteConversation(String conversationId) {
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM conversation_images WHERE conversation_id = ?")) {
            statement.setString(1, conversationId);
            return statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not delete conversation images", exception);
        }
    }

    private Optional<ConversationImageRecord> findByAttachmentId(Connection connection, String conversationId, String attachmentId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM conversation_images WHERE conversation_id = ? AND attachment_id = ?
                """)) {
            statement.setString(1, conversationId);
            statement.setString(2, attachmentId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(mapRow(resultSet)) : Optional.empty();
            }
        }
    }

    private int resolveSourceMessageOrdinal(Connection connection, String conversationId, String messageId) throws SQLException {
        if (messageId != null && !messageId.isBlank()) {
            try (PreparedStatement existing = connection.prepareStatement("""
                    SELECT source_message_ordinal FROM conversation_images
                    WHERE conversation_id = ? AND message_id = ? LIMIT 1
                    """)) {
                existing.setString(1, conversationId);
                existing.setString(2, messageId);
                try (ResultSet resultSet = existing.executeQuery()) {
                    if (resultSet.next()) {
                        return resultSet.getInt(1);
                    }
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(DISTINCT message_id) FROM conversation_images WHERE conversation_id = ?
                """)) {
            statement.setString(1, conversationId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return (resultSet.next() ? resultSet.getInt(1) : 0) + 1;
            }
        }
    }

    private int countRegistered(Connection connection, String conversationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM conversation_images WHERE conversation_id = ?")) {
            statement.setString(1, conversationId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getInt(1) : 0;
            }
        }
    }

    private void insert(Connection connection, ConversationImageRecord record) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT OR IGNORE INTO conversation_images
                (id, conversation_id, message_id, source_message_ordinal, ordinal_in_message, conversation_label,
                 attachment_id, workspace_id, original_file_name, media_type, size_bytes, created_at, expires_at, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, record.id());
            insert.setString(2, record.conversationId());
            insert.setString(3, record.messageId());
            insert.setInt(4, record.sourceMessageOrdinal());
            insert.setInt(5, record.ordinalInMessage());
            insert.setString(6, record.conversationLabel());
            insert.setString(7, record.attachmentId());
            insert.setString(8, record.workspaceId());
            insert.setString(9, record.originalFileName());
            insert.setString(10, record.mediaType());
            insert.setLong(11, record.sizeBytes());
            insert.setString(12, record.createdAt().toString());
            insert.setString(13, record.expiresAt().toString());
            insert.setString(14, record.status().name());
            insert.executeUpdate();
        }
    }

    private ConversationImageRecord mapRow(ResultSet resultSet) throws SQLException {
        return new ConversationImageRecord(
                resultSet.getString("id"),
                resultSet.getString("conversation_id"),
                resultSet.getString("message_id"),
                resultSet.getInt("source_message_ordinal"),
                resultSet.getInt("ordinal_in_message"),
                resultSet.getString("conversation_label"),
                resultSet.getString("attachment_id"),
                resultSet.getString("workspace_id"),
                resultSet.getString("original_file_name"),
                resultSet.getString("media_type"),
                resultSet.getLong("size_bytes"),
                Instant.parse(resultSet.getString("created_at")),
                Instant.parse(resultSet.getString("expires_at")),
                parseStatus(resultSet.getString("status"))
        );
    }

    private ConversationImageStatus parseStatus(String value) {
        try {
            return value == null ? ConversationImageStatus.INVALID : ConversationImageStatus.valueOf(value);
        } catch (IllegalArgumentException exception) {
            return ConversationImageStatus.INVALID;
        }
    }
}
