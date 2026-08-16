package com.jarvis.ollama;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Ollama native chat response.
 *
 * @param message assistant message
 * @param done whether generation is done
 * @param doneReason provider finish reason
 * @param evalCount completion token count
 * @param promptEvalCount prompt token count
 * @param totalDuration total generation duration in nanoseconds, when available
 * @param loadDuration model load duration in nanoseconds, when available
 * @param promptEvalDuration prompt evaluation duration in nanoseconds, when available
 * @param evalDuration token generation duration in nanoseconds, when available
 */
public record OllamaChatResponse(
        OllamaChatMessage message,
        Boolean done,
        @JsonProperty("done_reason") String doneReason,
        @JsonProperty("eval_count") Integer evalCount,
        @JsonProperty("prompt_eval_count") Integer promptEvalCount,
        @JsonProperty("total_duration") Long totalDuration,
        @JsonProperty("load_duration") Long loadDuration,
        @JsonProperty("prompt_eval_duration") Long promptEvalDuration,
        @JsonProperty("eval_duration") Long evalDuration
) {
}
