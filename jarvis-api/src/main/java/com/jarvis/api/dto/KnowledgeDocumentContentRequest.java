package com.jarvis.api.dto;

import java.util.UUID;

/**
 * Request containing document content.
 *
 * @param documentId document identifier
 * @param content content or appended text
 */
public record KnowledgeDocumentContentRequest(UUID documentId, String content) {
}
