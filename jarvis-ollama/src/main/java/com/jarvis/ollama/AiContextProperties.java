package com.jarvis.ollama;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Provider-facing AI context budget configuration.
 *
 * @param contextWindow maximum model context window
 * @param reservedOutputTokens tokens reserved for model output
 * @param repeatPenalty Ollama sampler repeat_penalty sent with every request; without an explicit
 *                      value Ollama/llama.cpp defaults to 1.0 (no penalty at all), which lets a
 *                      local model settle into repeating the exact same short token sequence
 *                      forever - especially likely at high temperature. 1.1 is Ollama's own
 *                      traditional default and is enough to break that failure mode.
 */
@ConfigurationProperties(prefix = "jarvis.ai")
public record AiContextProperties(
        int contextWindow,
        int reservedOutputTokens,
        double repeatPenalty
) {

    /**
     * Applies safe defaults for GPT-OSS tool and chat usage.
     */
    public AiContextProperties {
        contextWindow = contextWindow > 0 ? contextWindow : 16_384;
        reservedOutputTokens = reservedOutputTokens > 0 ? reservedOutputTokens : 2_048;
        if (reservedOutputTokens >= contextWindow) {
            reservedOutputTokens = Math.max(512, contextWindow / 8);
        }
        repeatPenalty = repeatPenalty > 0 ? repeatPenalty : 1.1;
    }

    /**
     * Returns the maximum input prompt token budget.
     *
     * @return input token budget
     */
    public int maxInputTokens() {
        return Math.max(1, contextWindow - reservedOutputTokens);
    }
}
