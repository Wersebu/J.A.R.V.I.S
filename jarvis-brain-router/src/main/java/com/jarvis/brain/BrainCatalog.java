package com.jarvis.brain;

import com.jarvis.common.ai.Brain;
import com.jarvis.common.ai.BrainType;
import com.jarvis.common.model.ActiveModelService;
import org.springframework.stereotype.Service;

/**
 * Provides access to configured logical brains.
 */
@Service
public class BrainCatalog {

    private static final String OLLAMA_PROVIDER = "ollama";

    private final BrainCatalogProperties properties;
    private final ActiveModelService activeModelService;

    /**
     * Creates the brain catalog.
     *
     * @param properties configured brain catalog properties
     * @param activeModelService single source of truth for the runtime-switchable active model
     */
    public BrainCatalog(BrainCatalogProperties properties, ActiveModelService activeModelService) {
        this.properties = properties;
        this.activeModelService = activeModelService;
    }

    /**
     * Resolves a logical brain by type.
     *
     * <p>For Ollama-backed brains, the model reflects the currently active model rather than the
     * static YAML default, so runtime model switches apply without touching prompt, tool, memory,
     * or history code.
     *
     * @param type logical brain type
     * @return configured brain
     */
    public Brain get(BrainType type) {
        BrainDefinition definition = properties.brains().get(type);
        if (definition == null) {
            throw new BrainRoutingException("Brain is not configured: " + type);
        }
        String model = OLLAMA_PROVIDER.equalsIgnoreCase(definition.provider())
                ? activeModelService.activeModel()
                : definition.model();
        return new Brain(type, definition.provider(), model, definition.description());
    }
}
