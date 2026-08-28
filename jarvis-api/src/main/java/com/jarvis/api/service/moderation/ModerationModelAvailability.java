package com.jarvis.api.service.moderation;

/**
 * Safe model availability status for moderation health checks.
 */
public record ModerationModelAvailability(boolean ollamaReachable, boolean modelAvailable) {
}
