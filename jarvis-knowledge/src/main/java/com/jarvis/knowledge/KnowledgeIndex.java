package com.jarvis.knowledge;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Metadata-only index for knowledge documents.
 */
public interface KnowledgeIndex {

    /**
     * Adds or updates a document.
     *
     * @param document document metadata
     */
    void upsert(KnowledgeDocument document);

    /**
     * Removes a document by relative path.
     *
     * @param relativePath relative document path
     * @return removed document, when present
     */
    Optional<KnowledgeDocument> removeByRelativePath(String relativePath);

    /**
     * Finds a document by id.
     *
     * @param id document id
     * @return matching document, when present
     */
    Optional<KnowledgeDocument> findById(UUID id);

    /**
     * Finds a document by relative path.
     *
     * @param relativePath relative document path
     * @return matching document, when present
     */
    Optional<KnowledgeDocument> findByRelativePath(String relativePath);

    /**
     * Lists all indexed document metadata.
     *
     * @return indexed documents
     */
    List<KnowledgeDocument> list();

    /**
     * Clears the index.
     */
    void clear();
}
