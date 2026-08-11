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
 */
public record OllamaChatResponse(
        OllamaChatMessage message,
        Boolean done,
        @JsonProperty("done_reason") String doneReason,
        @JsonProperty("eval_count") Integer evalCount,
        @JsonProperty("prompt_eval_count") Integer promptEvalCount
) {
}
