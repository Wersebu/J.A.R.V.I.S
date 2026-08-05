package com.jarvis.knowledge.workspace;

/**
 * Controls whether autonomous knowledge operations may write to disk.
 */
public enum KnowledgeWriteMode {
    /** Knowledge tools may only read. */
    READ_ONLY,
    /** Knowledge tools create drafts and require external approval before writing. */
    AUTO_DRAFT,
    /** Knowledge tools may write directly inside the configured knowledge root. */
    AUTO_WRITE
}
