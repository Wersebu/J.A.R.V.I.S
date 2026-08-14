package com.jarvis.common.model;

import java.util.EnumSet;
import java.util.Set;

/**
 * A model reported as installed by the local model provider.
 *
 * @param name model identifier, as understood by the provider (e.g. Ollama tag)
 * @param family model family, when reported by the provider
 * @param parameterSize human-readable parameter size, when reported by the provider
 * @param sizeBytes on-disk size in bytes, when reported by the provider
 * @param capabilities capabilities reported by the provider for this model
 */
public record InstalledModel(String name, String family, String parameterSize, long sizeBytes, Set<ModelCapability> capabilities) {

    /**
     * Normalizes the capability set.
     */
    public InstalledModel {
        capabilities = capabilities == null || capabilities.isEmpty()
                ? Set.of()
                : Set.copyOf(EnumSet.copyOf(capabilities));
    }

    /**
     * Returns whether this model supports the given capability.
     *
     * @param capability capability to check
     * @return true when supported
     */
    public boolean supports(ModelCapability capability) {
        return capabilities.contains(capability);
    }
}
