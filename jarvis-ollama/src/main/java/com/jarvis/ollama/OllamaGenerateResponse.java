package com.jarvis.ollama;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Minimal Ollama generate response used by Jarvis.
 *
 * @param response generated text
 * @param thinking native model reasoning text
 * @param done whether generation is complete
 * @param evalCount generated token count, when available
 * @param promptEvalCount prompt token count, when available
 * @param totalDuration total generation duration in nanoseconds, when available
 * @param loadDuration model load duration in nanoseconds, when available
 * @param promptEvalDuration prompt evaluation duration in nanoseconds, when available
 * @param evalDuration token generation duration in nanoseconds, when available
 */
public record OllamaGenerateResponse(
        String response,
        String thinking,
        Boolean done,
        @JsonProperty("eval_count") Integer evalCount,
        @JsonProperty("prompt_eval_count") Integer promptEvalCount,
        @JsonProperty("total_duration") Long totalDuration,
        @JsonProperty("load_duration") Long loadDuration,
        @JsonProperty("prompt_eval_duration") Long promptEvalDuration,
        @JsonProperty("eval_duration") Long evalDuration
) {
}
