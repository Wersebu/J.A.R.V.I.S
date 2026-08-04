package com.jarvis.api.dto;

/**
 * Response returned after memory reindexing.
 *
 * @param status operation status
 * @param indexedMemories number of indexed memories
 */
public record MemoryReindexResponse(String status, int indexedMemories) {
}
