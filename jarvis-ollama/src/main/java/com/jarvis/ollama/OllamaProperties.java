package com.jarvis.ollama;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the local Ollama HTTP API.
 *
 * @param baseUrl Ollama base URL
 */
@ConfigurationProperties(prefix = "jarvis.ai")
public record OllamaProperties(String baseUrl) {

}
