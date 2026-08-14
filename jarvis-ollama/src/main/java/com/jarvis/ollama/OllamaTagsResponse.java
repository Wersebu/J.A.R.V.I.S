package com.jarvis.ollama;

import java.util.List;

/**
 * Response from Ollama's {@code GET /api/tags} endpoint.
 *
 * @param models installed models
 */
public record OllamaTagsResponse(List<OllamaTagModel> models) {

    /**
     * Creates a response, defaulting a missing model list to empty.
     */
    public OllamaTagsResponse {
        models = models == null ? List.of() : models;
    }
}
