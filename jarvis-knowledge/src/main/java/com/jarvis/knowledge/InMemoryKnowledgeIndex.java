package com.jarvis.knowledge;

import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory metadata-only knowledge index.
 */
@Service
public class InMemoryKnowledgeIndex implements KnowledgeIndex {

    private final Map<UUID, KnowledgeDocument> documentsById = new ConcurrentHashMap<>();
    private final Map<String, UUID> idsByRelativePath = new ConcurrentHashMap<>();

    /**
     * Adds or updates a document.
     *
     * @param document document metadata
     */
    @Override
    public void upsert(KnowledgeDocument document) {
        documentsById.put(document.id(), document);
        idsByRelativePath.put(document.relativePath(), document.id());
    }

    /**
     * Removes a document by relative path.
     *
     * @param relativePath relative document path
     * @return removed document, when present
     */
    @Override
    public Optional<KnowledgeDocument> removeByRelativePath(String relativePath) {
        UUID id = idsByRelativePath.remove(relativePath);
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(documentsById.remove(id));
    }

    /**
     * Finds a document by id.
     *
     * @param id document id
     * @return matching document, when present
     */
    @Override
    public Optional<KnowledgeDocument> findById(UUID id) {
        return Optional.ofNullable(documentsById.get(id));
    }

    /**
     * Finds a document by relative path.
     *
     * @param relativePath relative document path
     * @return matching document, when present
     */
    @Override
    public Optional<KnowledgeDocument> findByRelativePath(String relativePath) {
        UUID id = idsByRelativePath.get(relativePath);
        return id == null ? Optional.empty() : findById(id);
    }

    /**
     * Lists all indexed document metadata.
     *
     * @return indexed documents
     */
    @Override
    public List<KnowledgeDocument> list() {
        return documentsById.values().stream()
                .sorted(Comparator.comparing(KnowledgeDocument::relativePath))
                .toList();
    }

    /**
     * Clears the index.
     */
    @Override
    public void clear() {
        documentsById.clear();
        idsByRelativePath.clear();
    }
}
