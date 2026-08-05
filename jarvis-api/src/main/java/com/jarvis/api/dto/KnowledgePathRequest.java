package com.jarvis.api.dto;

/**
 * Request containing a knowledge-relative path.
 *
 * @param path path relative to knowledge root
 */
public record KnowledgePathRequest(String path) {
}
