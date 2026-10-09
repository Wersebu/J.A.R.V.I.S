package com.jarvis.common.dto;

import com.jarvis.common.knowledge.KnowledgeMode;

import java.time.Instant;
import java.util.List;

/**
 * Request sent by a client to continue a conversation.
 *
 * @param conversationId stable conversation identifier
 * @param message user message text
 * @param clientRequestTimestamp timestamp captured by the client when Send was pressed
 * @param knowledgeMode requested knowledge strategy
 * @param attachments temporary attachment references to include as data context
 * @param activeCodingWorkspaceId user-selected Coding Workspace bound to this chat turn
 * @param activeCodingWorkspaceName display name of the selected Coding Workspace
 * @param activeCodingWorkspaceHost host of the selected Coding Workspace
 * @param workingDirectory folder on the user's PC this conversation works in (like a coding agent's
 *        working directory); relative pc__* paths and new terminal sessions start there
 * @param voiceMode the user talks by voice and hears the answer read aloud (short spoken style)
 */
public record ChatRequest(
        String conversationId,
        String message,
        Instant clientRequestTimestamp,
        KnowledgeMode knowledgeMode,
        List<AttachmentReference> attachments,
        String activeCodingWorkspaceId,
        String activeCodingWorkspaceName,
        String activeCodingWorkspaceHost,
        String workingDirectory,
        boolean voiceMode
) {

    /**
     * Normalizes optional values.
     */
    public ChatRequest {
        knowledgeMode = knowledgeMode == null ? KnowledgeMode.AUTO : knowledgeMode;
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        activeCodingWorkspaceId = activeCodingWorkspaceId == null ? "" : activeCodingWorkspaceId;
        activeCodingWorkspaceName = activeCodingWorkspaceName == null ? "" : activeCodingWorkspaceName;
        activeCodingWorkspaceHost = activeCodingWorkspaceHost == null ? "" : activeCodingWorkspaceHost;
        workingDirectory = workingDirectory == null ? "" : workingDirectory.strip();
    }

    /**
     * Creates a chat request without a working directory.
     */
    public ChatRequest(
            String conversationId,
            String message,
            Instant clientRequestTimestamp,
            KnowledgeMode knowledgeMode,
            List<AttachmentReference> attachments,
            String activeCodingWorkspaceId,
            String activeCodingWorkspaceName,
            String activeCodingWorkspaceHost,
            String workingDirectory
    ) {
        this(conversationId, message, clientRequestTimestamp, knowledgeMode, attachments, activeCodingWorkspaceId,
                activeCodingWorkspaceName, activeCodingWorkspaceHost, workingDirectory, false);
    }

    public ChatRequest(
            String conversationId,
            String message,
            Instant clientRequestTimestamp,
            KnowledgeMode knowledgeMode,
            List<AttachmentReference> attachments,
            String activeCodingWorkspaceId,
            String activeCodingWorkspaceName,
            String activeCodingWorkspaceHost
    ) {
        this(conversationId, message, clientRequestTimestamp, knowledgeMode, attachments, activeCodingWorkspaceId,
                activeCodingWorkspaceName, activeCodingWorkspaceHost, "");
    }

    /**
     * Creates a chat request with a client-side timestamp.
     *
     * @param conversationId stable conversation identifier
     * @param message user message text
     * @param clientRequestTimestamp timestamp captured by the client when Send was pressed
     */
    public ChatRequest(String conversationId, String message, Instant clientRequestTimestamp) {
        this(conversationId, message, clientRequestTimestamp, KnowledgeMode.AUTO, List.of(), "", "", "");
    }

    /**
     * Creates a chat request without a client-side timestamp.
     *
     * @param conversationId stable conversation identifier
     * @param message user message text
     */
    public ChatRequest(String conversationId, String message) {
        this(conversationId, message, null, KnowledgeMode.AUTO, List.of());
    }

    /**
     * Creates a chat request with explicit knowledge mode.
     *
     * @param conversationId stable conversation identifier
     * @param message user message text
     * @param clientRequestTimestamp timestamp captured by the client when Send was pressed
     * @param knowledgeMode requested knowledge strategy
     */
    public ChatRequest(String conversationId, String message, Instant clientRequestTimestamp, KnowledgeMode knowledgeMode) {
        this(conversationId, message, clientRequestTimestamp, knowledgeMode, List.of(), "", "", "");
    }

    /**
     * Creates a chat request with explicit knowledge mode and attachments.
     */
    public ChatRequest(
            String conversationId,
            String message,
            Instant clientRequestTimestamp,
            KnowledgeMode knowledgeMode,
            List<AttachmentReference> attachments
    ) {
        this(conversationId, message, clientRequestTimestamp, knowledgeMode, attachments, "", "", "");
    }
}
