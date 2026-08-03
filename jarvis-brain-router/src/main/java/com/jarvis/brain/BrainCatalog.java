package com.jarvis.brain;

import com.jarvis.common.ai.Brain;
import com.jarvis.common.ai.BrainType;
import org.springframework.stereotype.Service;

/**
 * Provides access to configured logical brains.
 */
@Service
public class BrainCatalog {

    private final BrainCatalogProperties properties;

    /**
     * Creates the brain catalog.
     *
     * @param properties configured brain catalog properties
     */
    public BrainCatalog(BrainCatalogProperties properties) {
        this.properties = properties;
    }

    /**
     * Resolves a logical brain by type.
     *
     * @param type logical brain type
     * @return configured brain
     */
    public Brain get(BrainType type) {
        BrainDefinition definition = properties.brains().get(type);
        if (definition == null) {
            throw new BrainRoutingException("Brain is not configured: " + type);
        }
        return new Brain(type, definition.provider(), definition.model(), definition.description());
    }
}
