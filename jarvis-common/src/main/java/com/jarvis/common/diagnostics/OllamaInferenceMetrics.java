package com.jarvis.common.diagnostics;

/**
 * Native Ollama inference timings converted to milliseconds.
 *
 * @param model model name
 * @param totalDurationMs Ollama total duration
 * @param loadDurationMs model load duration
 * @param promptEvalCount prompt tokens evaluated
 * @param promptEvalDurationMs prompt evaluation duration
 * @param evalCount generated token count
 * @param evalDurationMs generation duration
 * @param promptTokensPerSecond prompt evaluation speed
 * @param generationTokensPerSecond generation speed
 * @param requestStartToHeadersMs client wait until HTTP response headers
 * @param firstThinkingTokenMs time to first native thinking token
 * @param firstAnswerTokenMs time to first final answer token
 * @param modelLikelyWarm whether load duration suggests the model was already warm
 * @param bottleneck primary measured bottleneck
 */
public record OllamaInferenceMetrics(
        String model,
        long totalDurationMs,
        long loadDurationMs,
        long promptEvalCount,
        long promptEvalDurationMs,
        long evalCount,
        long evalDurationMs,
        double promptTokensPerSecond,
        double generationTokensPerSecond,
        long requestStartToHeadersMs,
        long firstThinkingTokenMs,
        long firstAnswerTokenMs,
        boolean modelLikelyWarm,
        OllamaBottleneckType bottleneck
) {
}
