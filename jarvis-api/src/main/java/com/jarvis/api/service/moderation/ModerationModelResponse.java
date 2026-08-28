package com.jarvis.api.service.moderation;

/**
 * Raw moderation-only model response.
 */
public record ModerationModelResponse(String content, long latencyMs, String modelVersion) {
}
