package com.jarvis.knowledge;

/**
 * Knowledge document index status.
 */
public enum DocumentStatus {
    /**
     * Document is newly discovered.
     */
    NEW,

    /**
     * Document metadata is indexed.
     */
    INDEXED,

    /**
     * Document was updated after initial indexing.
     */
    UPDATED,

    /**
     * Document was removed from disk.
     */
    REMOVED,

    /**
     * Document indexing failed.
     */
    ERROR
}
