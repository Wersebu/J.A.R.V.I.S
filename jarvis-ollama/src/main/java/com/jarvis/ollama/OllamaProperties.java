package com.jarvis.ollama;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the local Ollama HTTP API.
 *
 * @param baseUrl Ollama base URL
 * @param model default model
 * @param keepAlive Ollama keep_alive value
 * @param interactivePriority whether chat jobs get coordinator priority
 */
@ConfigurationProperties(prefix = "jarvis.ollama")
public record OllamaProperties(
        String baseUrl,
        String model,
        String keepAlive,
        boolean interactivePriority
) {

    /**
     * Creates properties with defaults.
     */
    public OllamaProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "http://localhost:11434";
        }
        if (model == null || model.isBlank()) {
            model = "gpt-oss:20b";
        }
        if (keepAlive == null || keepAlive.isBlank()) {
            keepAlive = "30m";
        }
    }
}
