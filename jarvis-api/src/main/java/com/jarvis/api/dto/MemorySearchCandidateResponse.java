package com.jarvis.api.dto;

/**
 * Scored memory candidate returned by debug search.
 *
 * @param id memory id
 * @param memory memory content
 * @param score score
 */
public record MemorySearchCandidateResponse(String id, String memory, double score) {
}
