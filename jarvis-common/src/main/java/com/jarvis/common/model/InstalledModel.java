package com.jarvis.common.model;

/**
 * A model reported as installed by the local model provider.
 *
 * @param name model identifier, as understood by the provider (e.g. Ollama tag)
 * @param family model family, when reported by the provider
 * @param parameterSize human-readable parameter size, when reported by the provider
 * @param sizeBytes on-disk size in bytes, when reported by the provider
 */
public record InstalledModel(String name, String family, String parameterSize, long sizeBytes) {
}
