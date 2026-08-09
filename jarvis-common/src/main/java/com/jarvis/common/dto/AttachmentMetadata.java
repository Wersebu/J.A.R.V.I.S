package com.jarvis.common.dto;

import java.time.Instant;

/**
 * Metadata for a temporary user attachment.
 *
 * @param attachmentId attachment identifier
 * @param workspaceId workspace identifier
 * @param originalFileName original user-visible file name
 * @param storedFileName safe internal file name
 * @param extension normalized extension
 * @param contentType detected or supplied content type
 * @param size file size in bytes
 * @param createdAt upload timestamp
 */
public record AttachmentMetadata(
        String attachmentId,
        String workspaceId,
        String originalFileName,
        String storedFileName,
        String extension,
        String contentType,
        long size,
        Instant createdAt
) {
}
