package com.jarvis.knowledge.vault.chunk;

import java.util.List;

/**
 * Fragment of a note produced by {@link MarkdownChunker}.
 *
 * @param ordinal position inside the document
 * @param headingPath heading hierarchy, outermost first
 * @param startLine first 1-based file line
 * @param endLine last 1-based file line
 * @param text fragment text exactly as in the file
 * @param embeddingText text sent to the embedding model (prefix + title/heading context + text)
 * @param tokenCount token count of {@code embeddingText}
 */
public record VaultChunk(
        int ordinal,
        List<String> headingPath,
        int startLine,
        int endLine,
        String text,
        String embeddingText,
        int tokenCount
) {

    /**
     * Creates an immutable chunk.
     */
    public VaultChunk {
        headingPath = headingPath == null ? List.of() : List.copyOf(headingPath);
    }

    /**
     * Returns the heading path joined with {@code " > "}.
     *
     * @return heading breadcrumb
     */
    public String headingBreadcrumb() {
        return String.join(" > ", headingPath);
    }
}
