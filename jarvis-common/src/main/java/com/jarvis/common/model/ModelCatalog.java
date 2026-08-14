package com.jarvis.common.model;

import java.util.List;

/**
 * Snapshot of installed models and the currently active model.
 *
 * @param models installed models, empty when the provider is unreachable
 * @param activeModel currently active model
 * @param providerReachable whether the model provider answered the catalog query
 * @param error human-readable error, when the provider was unreachable
 */
public record ModelCatalog(List<InstalledModel> models, String activeModel, boolean providerReachable, String error) {
}
