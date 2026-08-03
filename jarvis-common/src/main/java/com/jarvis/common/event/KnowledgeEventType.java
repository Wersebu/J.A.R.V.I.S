package com.jarvis.common.event;

/**
 * Event types emitted by the knowledge engine.
 */
public enum KnowledgeEventType {
    /**
     * A supported document was added to the index.
     */
    DOCUMENT_ADDED,

    /**
     * A supported document was updated in the index.
     */
    DOCUMENT_UPDATED,

    /**
     * A document was removed from the index.
     */
    DOCUMENT_REMOVED,

    /**
     * A full index rebuild completed.
     */
    INDEX_COMPLETED
}
