package com.jarvis.knowledge.workspace;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for autonomous knowledge workspace operations.
 *
 * @param writeMode default write policy
 * @param historyDirectory internal version history directory
 * @param draftsDirectory internal draft directory
 */
@ConfigurationProperties(prefix = "knowledge.workspace")
public record KnowledgeWorkspaceProperties(
        KnowledgeWriteMode writeMode,
        String historyDirectory,
        String draftsDirectory
) {

    /**
     * Applies safe defaults.
     */
    public KnowledgeWorkspaceProperties {
        writeMode = writeMode == null ? KnowledgeWriteMode.AUTO_DRAFT : writeMode;
        historyDirectory = historyDirectory == null || historyDirectory.isBlank() ? ".history" : historyDirectory;
        draftsDirectory = draftsDirectory == null || draftsDirectory.isBlank() ? ".drafts" : draftsDirectory;
    }
}
