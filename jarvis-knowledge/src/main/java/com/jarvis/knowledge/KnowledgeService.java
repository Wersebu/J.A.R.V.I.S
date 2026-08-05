package com.jarvis.knowledge;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Service API for knowledge document metadata indexing.
 */
public interface KnowledgeService {

    /**
     * Lists indexed knowledge document metadata.
     *
     * @return indexed documents
     */
    List<KnowledgeDocument> listDocuments();

    /**
     * Lists knowledge directories relative to the configured knowledge root.
     *
     * @return relative directory paths
     */
    List<String> listDirectories();

    /**
     * Finds a document by identifier.
     *
     * @param id document identifier
     * @return document metadata, when present
     */
    Optional<KnowledgeDocument> getDocument(UUID id);

    /**
     * Rebuilds the metadata index from the configured knowledge root.
     *
     * @return indexed documents
     */
    List<KnowledgeDocument> reindex();

    /**
     * Adds or updates one file in the metadata index.
     *
     * @param path file path
     * @param status status to assign
     * @return indexed document, when supported and present
     */
    Optional<KnowledgeDocument> indexFile(Path path, DocumentStatus status);

    /**
     * Removes one file from the metadata index.
     *
     * @param path file path
     * @return removed document, when present
     */
    Optional<KnowledgeDocument> removeFile(Path path);
}
