package com.jarvis.api.dto;

/**
 * Request to create a knowledge document.
 *
 * @param parentId parent node identifier
 * @param name document name
 * @param content document content
 */
public record KnowledgeCreateDocumentRequest(String parentId, String name, String content) {
}
