package com.jarvis.knowledge.retrieval;

import java.util.List;

/**
 * Result returned by a knowledge retriever.
 *
 * @param query original query
 * @param executionTimeMs execution time in milliseconds
 * @param documentsScanned number of indexed documents scanned
 * @param documents matching documents
 */
public record RetrievalResult(
        String query,
        long executionTimeMs,
        long documentsScanned,
        List<RetrievalDocument> documents
) {
}
