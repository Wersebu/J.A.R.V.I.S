package com.jarvis.api.dto.moderation;

/**
 * Safe, authenticated moderation health status.
 */
public record ModerationHealthResponse(
        boolean enabled,
        boolean modelConfigured,
        boolean ollamaReachable,
        boolean modelAvailable,
        String policyVersion
) {
}
