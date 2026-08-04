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
    INDEX_COMPLETED,

    /**
     * A knowledge retrieval request started.
     */
    KNOWLEDGE_RETRIEVAL_STARTED,

    /**
     * A knowledge retrieval request finished.
     */
    KNOWLEDGE_RETRIEVAL_FINISHED,

    /**
     * A knowledge context build started.
     */
    CONTEXT_BUILD_STARTED,

    /**
     * A knowledge context build finished.
     */
    CONTEXT_BUILD_FINISHED
}
