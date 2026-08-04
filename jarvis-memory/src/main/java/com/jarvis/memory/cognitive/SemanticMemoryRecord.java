package com.jarvis.memory.cognitive;

import java.time.Instant;
import java.util.UUID;
import com.jarvis.common.memory.MemoryCategory;
import com.jarvis.common.memory.MemoryPriority;

/**
 * Structured semantic memory fact.
 *
 * @param id memory identifier
 * @param subject fact subject
 * @param predicate fact predicate
 * @param value fact value
 * @param confidence deterministic confidence score
 * @param priority memory priority
 * @param category memory category
 * @param createdAt creation timestamp
 * @param updatedAt update timestamp
 * @param sourceConversation source conversation identifier
 */
public record SemanticMemoryRecord(
        UUID id,
        String subject,
        String predicate,
        String value,
        double confidence,
        MemoryPriority priority,
        MemoryCategory category,
        Instant createdAt,
        Instant updatedAt,
        String sourceConversation
) {
    /**
     * Creates a semantic memory with default priority and category.
     *
     * @param id memory identifier
     * @param subject fact subject
     * @param predicate fact predicate
     * @param value fact value
     * @param confidence deterministic confidence score
     * @param createdAt creation timestamp
     * @param updatedAt update timestamp
     * @param sourceConversation source conversation identifier
     */
    public SemanticMemoryRecord(
            UUID id,
            String subject,
            String predicate,
            String value,
            double confidence,
            Instant createdAt,
            Instant updatedAt,
            String sourceConversation
    ) {
        this(id, subject, predicate, value, confidence, MemoryPriority.NORMAL, MemoryCategory.SEMANTIC,
                createdAt, updatedAt, sourceConversation);
    }
}
