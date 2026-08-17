package com.jarvis.ollama;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Set;

/**
 * Configuration properties for the local Ollama HTTP API.
 *
 * @param baseUrl Ollama base URL
 * @param model default model
 * @param keepAlive Ollama keep_alive value
 * @param interactivePriority whether chat jobs get coordinator priority
 * @param visionCapabilityOverrides exact model names (matched case-insensitively) to always treat
 *         as vision-capable, even when Ollama's own {@code /api/tags}/{@code /api/show} response
 *         does not list {@code "vision"} in that model's capabilities. Capability detection is
 *         normally trusted as-is (never assumed from a model name) because it is provider-reported
 *         and self-updating - but some genuinely multimodal pulls (confirmed on the model's Ollama
 *         library page) still come back without "vision" here, most likely because the local Ollama
 *         build's capability auto-detection has not caught up with that architecture yet. This is
 *         an explicit, opt-in escape hatch for exactly that situation - empty by default, so it
 *         changes nothing until a model name is actually added.
 */
@ConfigurationProperties(prefix = "jarvis.ollama")
public record OllamaProperties(
        String baseUrl,
        String model,
        String keepAlive,
        boolean interactivePriority,
        Set<String> visionCapabilityOverrides
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
        visionCapabilityOverrides = visionCapabilityOverrides == null ? Set.of() : Set.copyOf(visionCapabilityOverrides);
    }
}
