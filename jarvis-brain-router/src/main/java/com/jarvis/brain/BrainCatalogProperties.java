package com.jarvis.brain;

import com.jarvis.common.ai.BrainType;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.EnumMap;
import java.util.Map;

/**
 * Configured catalog of logical AI brains.
 *
 * @param brains brain definitions keyed by logical type
 */
@ConfigurationProperties
public record BrainCatalogProperties(Map<BrainType, BrainDefinition> brains) {

    /**
     * Creates catalog properties with an enum-backed map.
     *
     * @param brains configured brain definitions
     */
    public BrainCatalogProperties {
        brains = brains == null ? new EnumMap<>(BrainType.class) : new EnumMap<>(brains);
    }
}
