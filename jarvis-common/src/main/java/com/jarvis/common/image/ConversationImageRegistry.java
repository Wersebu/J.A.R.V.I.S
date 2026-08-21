package com.jarvis.common.image;

import com.jarvis.common.dto.AttachmentMetadata;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Durable registry of every image ever attached to a conversation - metadata and safe references
 * only, never image bytes/base64 (see {@link ConversationImageRecord}'s javadoc). Every method is
 * scoped to a single {@code conversationId}; an image registered under one conversation can never
 * be looked up, selected, or mutated through another conversation's id.
 */
public interface ConversationImageRegistry {

    /**
     * Registers the images attached to one message, assigning each a stable conversation-scoped
     * label ({@code image-N}) and its {@code ordinalInMessage}/{@code sourceMessageOrdinal}.
     * Registering the same {@code (conversationId, attachmentId)} pair again is a no-op that
     * returns the existing record unchanged - callers do not need to de-duplicate themselves.
     *
     * @param conversationId owning conversation
     * @param messageId the request id of the message these images were uploaded with
     * @param images resolved attachment metadata for this message's images, in message order
     * @return the registered records, in the same order as {@code images}
     */
    List<ConversationImageRecord> registerImages(String conversationId, String messageId, List<AttachmentMetadata> images);

    /**
     * Returns every non-deleted image ever registered for a conversation, ordered by {@code
     * (sourceMessageOrdinal, ordinalInMessage)} - oldest message first. The returned status is
     * whatever was last stored; callers that need a live "is this file still really there" answer
     * must still verify existence themselves before reuse.
     *
     * @param conversationId owning conversation
     * @return every known image for this conversation, oldest first
     */
    List<ConversationImageRecord> findForConversation(String conversationId);

    /**
     * Looks up one image by attachment id, scoped to its owning conversation - returns empty when
     * the attachment id is unknown, deleted, or belongs to a different conversation.
     *
     * @param conversationId expected owning conversation
     * @param attachmentId attachment id to look up
     * @return the record, if it exists and belongs to {@code conversationId}
     */
    Optional<ConversationImageRecord> findByAttachmentId(String conversationId, String attachmentId);

    /**
     * Updates one record's status - scoped to its owning conversation, a no-op if the id does not
     * belong to {@code conversationId}.
     *
     * @param conversationId expected owning conversation
     * @param attachmentId attachment id to update
     * @param status new status
     */
    void updateStatus(String conversationId, String attachmentId, ConversationImageStatus status);

    /**
     * Marks every still-{@link ConversationImageStatus#AVAILABLE} record whose retention window has
     * elapsed as {@link ConversationImageStatus#EXPIRED} - the scheduled sweep counterpart to {@link
     * com.jarvis.common.image.ConversationImageRecord#isPastRetention(Instant)}.
     *
     * @param now reference instant
     * @return number of records updated
     */
    int expireOlderThan(Instant now);

    /**
     * Deletes every image record for a conversation - called when the conversation itself is
     * deleted. Never touches the physical temporary-workspace files; those remain the temporary
     * workspace's own cleanup responsibility.
     *
     * @param conversationId conversation to purge
     * @return number of records deleted
     */
    int deleteConversation(String conversationId);
}
