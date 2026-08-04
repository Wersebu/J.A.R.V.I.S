package com.jarvis.common.memory;

import java.time.Instant;
import java.util.UUID;

/**
 * Provider-independent memory record exposed to the pipeline and API.
 *
 * @param id memory identifier
 * @param type memory type
 * @param title short display title
 * @param content memory content
 * @param confidence deterministic confidence score
 * @param createdAt creation timestamp
 * @param updatedAt update timestamp
 * @param sourceConversation source conversation identifier
 */
public record MemoryRecord(
        UUID id,
        MemoryType type,
        String title,
        String content,
        double confidence,
        Instant createdAt,
        Instant updatedAt,
        String sourceConversation
) {
}
