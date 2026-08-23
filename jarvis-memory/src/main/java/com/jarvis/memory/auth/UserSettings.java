package com.jarvis.memory.auth;

import java.time.Instant;

public record UserSettings(
        String userId,
        String globalPrompt,
        Instant updatedAt
) {
}
