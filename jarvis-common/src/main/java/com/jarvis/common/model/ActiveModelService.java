package com.jarvis.common.model;

import java.util.Set;

/**
 * Single source of truth for the currently active local AI model.
 *
 * <p>The active model is runtime-switchable: a switch takes effect for requests started after it
 * completes, never for a request already in flight. Implementations must be thread-safe.
 */
public interface ActiveModelService {

    /**
     * Returns the currently active model name.
     *
     * @return active model
     */
    String activeModel();

    /**
     * Returns the capabilities reported by the provider for the currently active model.
     *
     * <p>Empty when the provider is unreachable, the active model is not in its installed list, or
     * it reports no capabilities - callers gating a capability-sensitive feature (e.g. vision) must
     * treat an empty result as "not supported", the same as an explicit absence, so a request never
     * reaches a model that cannot actually handle it.
     *
     * @return active model capabilities
     */
    Set<ModelCapability> activeModelCapabilities();

    /**
     * Returns the installed models and the currently active model.
     *
     * @return model catalog
     */
    ModelCatalog catalog();

    /**
     * Switches the active model, after validating it against the provider's installed models.
     *
     * @param requestedModel model to activate
     * @return switch result; rejected when the model is blank, unknown, or the provider is unreachable
     */
    ModelSwitchResult switchTo(String requestedModel);
}
