package com.jarvis.api.dto;

import java.util.List;

/**
 * Response for {@code GET /api/models}: the installed models and the currently active one.
 *
 * @param models installed models, empty when the provider is unreachable
 * @param activeModel currently active model
 * @param providerReachable whether the model provider answered the catalog query
 * @param error human-readable error, when the provider was unreachable
 */
public record ModelsResponse(List<ModelInfoResponse> models, String activeModel, boolean providerReachable, String error) {
}
