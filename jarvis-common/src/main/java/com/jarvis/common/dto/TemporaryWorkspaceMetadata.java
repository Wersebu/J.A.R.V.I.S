package com.jarvis.common.dto;

import java.time.Instant;
import java.util.List;

/**
 * Metadata describing one isolated temporary workspace.
 *
 * @param workspaceId workspace identifier
 * @param conversationId optional conversation identifier
 * @param createdAt creation timestamp
 * @param lastAccessAt last real operation timestamp
 * @param currentSize current workspace size in bytes
 * @param attachments input attachments currently registered in the workspace
 */
public record TemporaryWorkspaceMetadata(
        String workspaceId,
        String conversationId,
        Instant createdAt,
        Instant lastAccessAt,
        long currentSize,
        List<AttachmentMetadata> attachments
) {
    /**
     * Normalizes optional lists.
     */
    public TemporaryWorkspaceMetadata {
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }
}
