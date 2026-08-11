package com.jarvis.ollama;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Provider-facing AI context budget configuration.
 *
 * @param contextWindow maximum model context window
 * @param reservedOutputTokens tokens reserved for model output
 */
@ConfigurationProperties(prefix = "jarvis.ai")
public record AiContextProperties(
        int contextWindow,
        int reservedOutputTokens
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
