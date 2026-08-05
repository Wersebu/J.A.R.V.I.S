package com.jarvis.api.dto;

import java.util.List;

/**
 * Debug response for memory retrieval.
 *
 * @param normalizedQuery normalized query tokens
 * @param candidates scored candidates
 * @param selected selected memory content
 */
public record MemorySearchDebugResponse(
        List<String> normalizedQuery,
        List<MemorySearchCandidateResponse> candidates,
        String selected
) {
}
