package com.jarvis.ollama;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the local Ollama HTTP API.
 *
 * @param provider configured AI provider name
 * @param baseUrl Ollama base URL
 * @param model default model name
 */
@ConfigurationProperties(prefix = "jarvis.ai")
public record OllamaProperties(String provider, String baseUrl, String model) {

}
