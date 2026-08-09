package com.jarvis.api.service;

import com.jarvis.common.dto.AttachmentMetadata;
import com.jarvis.common.dto.AttachmentReference;
import com.jarvis.common.dto.TemporaryWorkspaceMetadata;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * API-facing service for isolated temporary workspaces and chat attachments.
 */
public interface TemporaryWorkspaceService {

    /**
     * Creates a new temporary workspace.
     *
     * @param conversationId optional conversation identifier
     * @return workspace metadata
     */
    TemporaryWorkspaceMetadata createWorkspace(String conversationId);

    /**
     * Stores one or more uploaded input attachments.
     *
     * @param workspaceId workspace identifier
     * @param conversationId optional conversation identifier
     * @param files uploaded files
     * @return stored attachment metadata
     */
    List<AttachmentMetadata> storeInput(String workspaceId, String conversationId, List<MultipartFile> files);

    /**
     * Reads an attachment as UTF-8 text for prompt construction.
     *
     * @param reference attachment reference
     * @return readable attachment
     */
    ReadableAttachment readAttachment(AttachmentReference reference);

    /**
     * Returns workspace metadata.
     *
     * @param workspaceId workspace identifier
     * @return workspace metadata
     */
    TemporaryWorkspaceMetadata metadata(String workspaceId);

    /**
     * Deletes a complete temporary workspace.
     *
     * @param workspaceId workspace identifier
     */
    void deleteWorkspace(String workspaceId);

    /**
     * Performs cleanup for expired workspaces.
     *
     * @return number of removed workspaces
     */
    int cleanupExpired();

    /**
     * Text attachment loaded from a temporary workspace.
     *
     * @param metadata attachment metadata
     * @param content UTF-8 text content
     */
    record ReadableAttachment(AttachmentMetadata metadata, String content) {
    }
}
