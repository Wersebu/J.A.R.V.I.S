package com.jarvis.memory.cognitive;

import java.time.Instant;
import java.util.UUID;

/**
 * Structured semantic memory fact.
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
public record SemanticMemoryRecord(
        UUID id,
        String subject,
        String predicate,
        String value,
        double confidence,
        Instant createdAt,
        Instant updatedAt,
        String sourceConversation
) {
}
