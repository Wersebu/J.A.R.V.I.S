package com.jarvis.common.ai;

/**
 * Token usage reported by a model provider.
 *
 * @param promptTokens prompt tokens
 * @param completionTokens completion tokens
 * @param totalTokens total tokens
 */
public record ModelUsage(int promptTokens, int completionTokens, int totalTokens) {
}
