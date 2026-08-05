package com.jarvis.api.dto;

import java.util.List;

/**
 * Complete cognitive graph snapshot.
 *
 * @param revision graph revision
 * @param generatedAt generation timestamp
 * @param nodes graph nodes
 * @param edges graph edges
 */
public record CognitiveGraphResponse(
        long revision,
        String generatedAt,
        List<CognitiveGraphNodeResponse> nodes,
        List<CognitiveGraphEdgeResponse> edges
) {
}
