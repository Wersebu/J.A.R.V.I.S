package com.jarvis.knowledge.vault.index;

import java.util.List;

/**
 * Fragment row of the chunk index.
 *
 * @param chunkId stable fragment id (document id + ordinal + content hash)
 * @param documentId owning document id
 * @param ordinal position in the document
 * @param headingPath heading hierarchy
 * @param startLine first 1-based line
 * @param endLine last 1-based line
 * @param text fragment text
 * @param chunkHash SHA-256 of the embedded text
 * @param documentHash content hash of the document version the fragment came from
 * @param documentVersion declared document version, or {@code ""}
 * @param tokenCount tokens of the embedded text
 * @param embedding vector, or {@code null} while pending
 * @param embeddingFingerprint embedding space the vector belongs to
 * @param embeddingDimension vector dimension (0 while pending)
 * @param embeddingError last embedding error for this fragment
 */
public record StoredChunk(
        String chunkId,
        String documentId,
        int ordinal,
        List<String> headingPath,
        int startLine,
        int endLine,
        String text,
        String chunkHash,
        String documentHash,
        String documentVersion,
        int tokenCount,
        float[] embedding,
        String embeddingFingerprint,
        int embeddingDimension,
        String embeddingError
) {

    /**
     * Creates an immutable row.
     */
    public StoredChunk {
        headingPath = headingPath == null ? List.of() : List.copyOf(headingPath);
        embeddingFingerprint = embeddingFingerprint == null ? "" : embeddingFingerprint;
        embeddingError = embeddingError == null ? "" : embeddingError;
    }

    /**
     * Returns a copy with an embedding.
     *
     * @param vector vector
     * @param fingerprint embedding space
     * @return updated row
     */
    public StoredChunk withEmbedding(float[] vector, String fingerprint) {
        return new StoredChunk(chunkId, documentId, ordinal, headingPath, startLine, endLine, text, chunkHash, documentHash,
                documentVersion, tokenCount, vector, fingerprint, vector == null ? 0 : vector.length, "");
    }

    /**
     * Returns a copy with an embedding error.
     *
     * @param error error
     * @return updated row
     */
    public StoredChunk withEmbeddingError(String error) {
        return new StoredChunk(chunkId, documentId, ordinal, headingPath, startLine, endLine, text, chunkHash, documentHash,
                documentVersion, tokenCount, null, "", 0, error);
    }

    /**
     * Returns whether the fragment has a vector in the given embedding space.
     *
     * @param fingerprint embedding space
     * @param dimension expected dimension
     * @return true when comparable
     */
    public boolean embeddedIn(String fingerprint, int dimension) {
        return embedding != null && !fingerprint.isEmpty() && fingerprint.equals(embeddingFingerprint)
                && embedding.length == dimension;
    }
}
