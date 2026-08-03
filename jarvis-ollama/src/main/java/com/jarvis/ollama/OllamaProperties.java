package com.jarvis.ollama;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the local Ollama HTTP API.
 *
 * @param baseUrl Ollama base URL
 * @param model default model name
 */
@ConfigurationProperties(prefix = "jarvis.ollama")
public record OllamaProperties(String baseUrl, String model) {

    /**
     * Creates properties with defaults for local Ollama.
     *
     * @param baseUrl configured base URL
     * @param model configured model name
     */
    public OllamaProperties {
        baseUrl = baseUrl == null || baseUrl.isBlank() ? "http://localhost:11434" : baseUrl;
        model = model == null || model.isBlank() ? "llama3.1" : model;
    }
}
