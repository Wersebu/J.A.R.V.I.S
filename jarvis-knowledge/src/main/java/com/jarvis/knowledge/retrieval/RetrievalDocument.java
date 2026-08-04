package com.jarvis.knowledge.retrieval;

import java.util.UUID;

/**
 * Document returned by the retrieval engine.
 *
 * @param documentId source document identifier
 * @param title document title
 * @param category document category
 * @param relativePath document path relative to the knowledge root
 * @param score retrieval score
 * @param preview indexed preview
 */
public record RetrievalDocument(
        UUID documentId,
        String title,
        String category,
        String relativePath,
        int score,
        String preview
) {
}
