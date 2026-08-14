package com.jarvis.common.model;

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
