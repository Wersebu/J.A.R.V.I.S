package com.jarvis.memory.auth;

import java.time.Instant;

public record AuthenticatedSession(
        String token,
        String sessionId,
        UserAccount user,
        Instant expiresAt
) {
}
