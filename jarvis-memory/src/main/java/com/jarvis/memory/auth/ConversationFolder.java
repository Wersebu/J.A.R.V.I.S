package com.jarvis.memory.auth;

import java.time.Instant;

public record ConversationFolder(
        String id,
        String userId,
        String name,
        String systemPrompt,
        Instant createdAt,
        Instant updatedAt
) {
}
