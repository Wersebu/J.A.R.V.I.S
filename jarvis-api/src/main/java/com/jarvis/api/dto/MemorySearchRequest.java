package com.jarvis.api.dto;

/**
 * Request body for memory retrieval debugging.
 *
 * @param query query text
 */
public record MemorySearchRequest(String query) {
}
