package com.jarvis.knowledge.workspace;

import java.time.Instant;

/**
 * Version entry for a modified knowledge document.
 *
 * @param versionId stored version identifier
 * @param documentId document identifier
 * @param relativePath document path at the time of capture
 * @param timestamp version timestamp
 * @param author author label
 * @param summary short change summary
 * @param conversationId source conversation identifier
 * @param requestId source request identifier
 * @param tool tool that caused the change
 * @param reason reason for the change
 * @param reasoningSummary safe AI reasoning summary
 * @param previousVersionPath internal previous content path
 */
public record KnowledgeVersion(
        String versionId,
        String documentId,
        String relativePath,
        Instant timestamp,
        String author,
        String summary,
        String conversationId,
        String requestId,
        String tool,
        String reason,
        String reasoningSummary,
        String previousVersionPath
) {
}
