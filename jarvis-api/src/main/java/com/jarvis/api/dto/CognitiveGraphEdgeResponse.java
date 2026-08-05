package com.jarvis.api.dto;

/**
 * Cognitive graph edge response.
 *
 * @param source source node id
 * @param target target node id
 * @param type edge type
 */
public record CognitiveGraphEdgeResponse(
        String source,
        String target,
        String type
) {
}
