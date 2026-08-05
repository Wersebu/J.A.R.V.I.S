package com.jarvis.api.dto;

import java.util.List;

/**
 * Complete cognitive graph snapshot.
 *
 * @param nodes graph nodes
 * @param edges graph edges
 */
public record CognitiveGraphResponse(
        List<CognitiveGraphNodeResponse> nodes,
        List<CognitiveGraphEdgeResponse> edges
) {
}
