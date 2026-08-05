package com.jarvis.api.dto;

import java.util.List;

/**
 * Debug response for memory retrieval.
 *
 * @param embeddingModel embedding model
 * @param embeddingTimeMs query embedding generation time
 * @param topResults scored candidates
 * @param normalizedQuery normalized fallback query tokens
 * @param selected selected memory content
 */
public record MemorySearchDebugResponse(
        String embeddingModel,
        long embeddingTimeMs,
        List<MemorySearchCandidateResponse> topResults,
        List<String> normalizedQuery,
        String selected
) {
}
