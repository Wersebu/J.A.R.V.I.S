package com.jarvis.knowledge;

import java.time.Instant;
import java.util.UUID;

/**
 * Metadata for a document in the knowledge library.
 *
 * @param id stable document identifier
 * @param title document title
 * @param relativePath path relative to the knowledge root
 * @param category top-level knowledge category
 * @param extension file extension
 * @param fileSize file size in bytes
 * @param created file creation timestamp
 * @param modified file modification timestamp
 * @param sha256 SHA-256 file hash
 * @param preview extracted preview
 * @param status document status
 */
public record KnowledgeDocument(
        UUID id,
        String title,
        String relativePath,
        String category,
        String extension,
        long fileSize,
        Instant created,
        Instant modified,
        String sha256,
        String preview,
        DocumentStatus status
) {
}
