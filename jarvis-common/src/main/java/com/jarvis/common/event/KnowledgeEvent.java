package com.jarvis.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Event emitted by the knowledge engine.
 *
 * @param type event type
 * @param timestamp event timestamp
 * @param documentId document identifier, when available
 * @param relativePath document relative path, when available
 */
public record KnowledgeEvent(
        KnowledgeEventType type,
        Instant timestamp,
        UUID documentId,
        String relativePath
) {

    /**
     * Creates a knowledge event for a document.
     *
     * @param type event type
     * @param documentId document identifier
     * @param relativePath document relative path
     * @return knowledge event
     */
    public static KnowledgeEvent document(KnowledgeEventType type, UUID documentId, String relativePath) {
        return new KnowledgeEvent(type, Instant.now(), documentId, relativePath);
    }

    /**
     * Creates an index-completed event.
     *
     * @return knowledge event
     */
    public static KnowledgeEvent indexCompleted() {
        return new KnowledgeEvent(KnowledgeEventType.INDEX_COMPLETED, Instant.now(), null, null);
    }

    /**
     * Creates a retrieval lifecycle event.
     *
     * @param type retrieval event type
     * @return knowledge event
     */
    public static KnowledgeEvent retrieval(KnowledgeEventType type) {
        return new KnowledgeEvent(type, Instant.now(), null, null);
    }

    /**
     * Creates a context build lifecycle event.
     *
     * @param type context event type
     * @return knowledge event
     */
    public static KnowledgeEvent context(KnowledgeEventType type) {
        return new KnowledgeEvent(type, Instant.now(), null, null);
    }
}
