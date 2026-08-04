package com.jarvis.memory.cognitive;

import java.time.Instant;
import java.util.UUID;

/**
 * Structured procedural memory workflow.
 *
 * @param id memory identifier
 * @param name procedure name
 * @param steps procedure steps
 * @param confidence deterministic confidence score
 * @param createdAt creation timestamp
 * @param updatedAt update timestamp
 * @param sourceConversation source conversation identifier
 */
public record ProceduralMemoryRecord(
        UUID id,
        String name,
        String steps,
        double confidence,
        Instant createdAt,
        Instant updatedAt,
        String sourceConversation
) {
}
