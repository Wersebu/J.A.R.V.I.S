package com.jarvis.memory.cognitive;

import java.time.Instant;
import java.util.UUID;

/**
 * Structured episodic memory event.
 *
 * @param id memory identifier
 * @param title event title
 * @param description event description
 * @param importance deterministic importance score
 * @param createdAt creation timestamp
 * @param sourceConversation source conversation identifier
 */
public record EpisodicMemoryRecord(
        UUID id,
        String title,
        String description,
        double importance,
        Instant createdAt,
        String sourceConversation
) {
}
