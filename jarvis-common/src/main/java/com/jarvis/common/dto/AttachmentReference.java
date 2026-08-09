package com.jarvis.common.dto;

/**
 * Safe reference to a temporary attachment uploaded before a chat request.
 *
 * @param workspaceId temporary workspace identifier
 * @param attachmentId attachment identifier inside the workspace
 */
public record AttachmentReference(String workspaceId, String attachmentId) {
}
