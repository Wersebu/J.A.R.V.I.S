package com.jarvis.common.memory;

import java.time.Instant;
import java.util.UUID;

/**
 * Single message stored in a conversation history.
 *
 * @param id message identifier
 * @param conversationId stable conversation identifier
 * @param requestId request identifier that produced this message
 * @param role message author role
 * @param content message content
 * @param createdAt creation timestamp
 * @param sequenceNumber monotonic sequence number inside the conversation
 * @param messageType message type
 * @param status message status
 */
public record ConversationMessage(
        UUID id,
        String conversationId,
        String requestId,
        MessageRole role,
        String content,
        Instant createdAt,
        long sequenceNumber,
        ConversationMessageType messageType,
        ConversationMessageStatus status
) {

    /**
     * Creates a legacy chat message.
     *
     * @param role message role
     * @param content message content
     * @param createdAt creation timestamp
     */
    public ConversationMessage(MessageRole role, String content, Instant createdAt) {
        this(UUID.randomUUID(), "", "", role, content, createdAt, 0L,
                ConversationMessageType.CHAT, ConversationMessageStatus.FINAL);
    }

    /**
     * Creates a finalized chat message for a request.
     *
     * @param conversationId stable conversation identifier
     * @param requestId request identifier
     * @param role message role
     * @param content message content
     * @param createdAt creation timestamp
     * @return message
     */
    public static ConversationMessage chat(
            String conversationId,
            String requestId,
            MessageRole role,
            String content,
            Instant createdAt
    ) {
        return new ConversationMessage(UUID.randomUUID(), conversationId, requestId, role, content, createdAt, 0L,
                ConversationMessageType.CHAT, ConversationMessageStatus.FINAL);
    }

    /**
     * Creates a normalized message.
     */
    public ConversationMessage {
        id = id == null ? UUID.randomUUID() : id;
        conversationId = conversationId == null ? "" : conversationId;
        requestId = requestId == null ? "" : requestId;
        role = role == null ? MessageRole.USER : role;
        content = content == null ? "" : content;
        createdAt = createdAt == null ? Instant.now() : createdAt;
        messageType = messageType == null ? ConversationMessageType.CHAT : messageType;
        status = status == null ? ConversationMessageStatus.FINAL : status;
    }
}
