package com.jarvis.common.image;

import java.time.Instant;

/**
 * One image registered against a conversation - durable metadata only, never the image's own
 * bytes/base64 (those stay in the temporary workspace on disk and must be re-read and re-verified
 * before every reuse; a record existing here is never proof the backing file still exists).
 *
 * @param id internal registry record id
 * @param conversationId owning conversation - an image never crosses to another conversation
 * @param messageId the request id of the message this image was uploaded with
 * @param sourceMessageOrdinal 1-based position of {@code messageId} among every message in this
 *         conversation that has ever registered an image, assigned once at registration and never
 *         renumbered - the "Source message: N" the model/UI refer to
 * @param ordinalInMessage 1-based position of this image among the images uploaded in the same
 *         source message (image order within that message is preserved)
 * @param conversationLabel stable, human-readable conversation-scoped label (e.g. {@code image-3})
 * @param attachmentId the real workspace attachment id
 * @param workspaceId the temporary workspace this attachment lives in
 * @param originalFileName original user-visible file name
 * @param mediaType normalized file extension/media type (e.g. {@code png})
 * @param sizeBytes file size in bytes at registration time
 * @param createdAt when the image was originally uploaded - retention is always counted from here,
 *         never reset by later reuse
 * @param expiresAt when this image's retention window ends
 * @param status current lifecycle status (see {@link ConversationImageStatus})
 */
public record ConversationImageRecord(
        String id,
        String conversationId,
        String messageId,
        int sourceMessageOrdinal,
        int ordinalInMessage,
        String conversationLabel,
        String attachmentId,
        String workspaceId,
        String originalFileName,
        String mediaType,
        long sizeBytes,
        Instant createdAt,
        Instant expiresAt,
        ConversationImageStatus status
) {

    /**
     * Normalizes null text fields.
     */
    public ConversationImageRecord {
        id = id == null ? "" : id;
        conversationId = conversationId == null ? "" : conversationId;
        messageId = messageId == null ? "" : messageId;
        conversationLabel = conversationLabel == null ? "" : conversationLabel;
        attachmentId = attachmentId == null ? "" : attachmentId;
        workspaceId = workspaceId == null ? "" : workspaceId;
        originalFileName = originalFileName == null ? "" : originalFileName;
        mediaType = mediaType == null ? "" : mediaType;
        status = status == null ? ConversationImageStatus.INVALID : status;
    }

    /**
     * Returns a copy with an updated status - every other field (including {@code createdAt}/{@code
     * expiresAt}) is preserved unchanged, since retention is never extended by later reuse.
     *
     * @param newStatus the new status
     * @return updated record
     */
    public ConversationImageRecord withStatus(ConversationImageStatus newStatus) {
        return new ConversationImageRecord(id, conversationId, messageId, sourceMessageOrdinal, ordinalInMessage,
                conversationLabel, attachmentId, workspaceId, originalFileName, mediaType, sizeBytes, createdAt,
                expiresAt, newStatus);
    }

    /**
     * Whether this record's retention window has elapsed, purely from the timestamp - independent
     * of whatever status is currently stored (the stored status may not have been swept yet).
     *
     * @param now reference instant
     * @return true when past {@link #expiresAt()}
     */
    public boolean isPastRetention(Instant now) {
        return expiresAt != null && now.isAfter(expiresAt);
    }
}
