package com.jarvis.memory.auth;

import java.time.Instant;

public record UserAccount(
        String id,
        String email,
        String displayName,
        String role,
        boolean active,
        Instant createdAt,
        Instant updatedAt
) {
}
