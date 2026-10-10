package com.jarvis.knowledge.vault.index;

import java.util.List;

/**
 * Document row of the chunk index.
 *
 * @param documentId stable document identifier (frontmatter {@code id}, or an id kept across moves)
 * @param path vault-relative path
 * @param title title
 * @param type type ({@code workflow}, {@code knowledge}, ...)
 * @param status status
 * @param project project
 * @param tags tags
 * @param aliases aliases
 * @param version declared version
 * @param updated declared update date
 * @param idSource {@code frontmatter} or {@code index}
 * @param contentHash SHA-256 of the file bytes (the document version token)
 * @param size file size
 * @param modifiedMillis file modification time
 * @param lineCount number of lines
 * @param tokenCount estimated tokens of the whole file
 * @param indexedAt ISO timestamp
 * @param state {@code INDEXED}, {@code EMPTY}, {@code EXCLUDED_SECRET}, {@code EXCLUDED_PROMPT} or {@code ERROR}
 * @param error error / warning text
 * @param links resolved links as {@code kind|target|resolvedPath}
 * @param workflow whether the document is a workflow (procedure)
 */
public record StoredDocument(
        String documentId,
        String path,
        String title,
        String type,
        String status,
        String project,
        List<String> tags,
        List<String> aliases,
        String version,
        String updated,
        String idSource,
        String contentHash,
        long size,
        long modifiedMillis,
        int lineCount,
        int tokenCount,
        String indexedAt,
        String state,
        String error,
        List<String> links,
        boolean workflow
) {

    /** Indexed and searchable. */
    public static final String INDEXED = "INDEXED";
    /** No content to index. */
    public static final String EMPTY = "EMPTY";
    /** Looks like it contains credentials; never chunked. */
    public static final String EXCLUDED_SECRET = "EXCLUDED_SECRET";
    /** Copy of the system prompt; never chunked. */
    public static final String EXCLUDED_PROMPT = "EXCLUDED_PROMPT";
    /** Could not be read or parsed. */
    public static final String ERROR = "ERROR";

    /**
     * Creates an immutable row.
     */
    public StoredDocument {
        tags = tags == null ? List.of() : List.copyOf(tags);
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        links = links == null ? List.of() : List.copyOf(links);
    }

    /**
     * Returns a copy at a new path.
     *
     * @param newPath new path
     * @param newModified new modification time
     * @return moved row
     */
    public StoredDocument movedTo(String newPath, long newModified) {
        return new StoredDocument(documentId, newPath, title, type, status, project, tags, aliases, version, updated, idSource,
                contentHash, size, newModified, lineCount, tokenCount, indexedAt, state, error, links, workflow);
    }
}
