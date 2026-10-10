package com.jarvis.knowledge.vault.search;

import java.util.List;

/**
 * One fragment returned by a vault search, with its source.
 *
 * @param rank 1-based rank
 * @param chunkId fragment id
 * @param documentId stable document id
 * @param path vault-relative path
 * @param title document title
 * @param headingPath heading hierarchy
 * @param startLine first line
 * @param endLine last line
 * @param text fragment text
 * @param tokenCount fragment tokens
 * @param type document type
 * @param status document status
 * @param project project
 * @param tags tags
 * @param documentVersion declared document version
 * @param documentHash content hash of the indexed document version
 * @param updated declared update date
 * @param workflow whether the source document is a workflow
 * @param lexicalScore BM25 score (0 when not matched lexically)
 * @param semanticSimilarity cosine similarity, or {@code null} when not computed
 * @param fusedScore reciprocal rank fusion score - a ranking signal, not a probability of correctness
 * @param matchedBy {@code lexical} and/or {@code semantic}
 */
public record VaultSearchHit(
        int rank,
        String chunkId,
        String documentId,
        String path,
        String title,
        List<String> headingPath,
        int startLine,
        int endLine,
        String text,
        int tokenCount,
        String type,
        String status,
        String project,
        List<String> tags,
        String documentVersion,
        String documentHash,
        String updated,
        boolean workflow,
        double lexicalScore,
        Double semanticSimilarity,
        double fusedScore,
        List<String> matchedBy
) {
}
