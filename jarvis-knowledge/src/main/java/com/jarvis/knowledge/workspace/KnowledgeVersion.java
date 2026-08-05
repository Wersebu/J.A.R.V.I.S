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
 * @param previousVersionPath internal previous content path
 */
public record KnowledgeVersion(
        String versionId,
        String documentId,
        String relativePath,
        Instant timestamp,
        String author,
        String summary,
        String previousVersionPath
) {
}
