package com.jarvis.knowledge;

import com.jarvis.common.event.KnowledgeEvent;
import com.jarvis.common.event.KnowledgeEventType;
import com.jarvis.knowledge.extract.DocumentExtractor;
import com.jarvis.knowledge.extract.DocumentExtractorRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Default in-memory knowledge service implementation.
 */
@Service
public class DefaultKnowledgeService implements KnowledgeService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultKnowledgeService.class);

    private final KnowledgeProperties properties;
    private final KnowledgeIndex index;
    private final SupportedFileTypes supportedFileTypes;
    private final DocumentExtractorRegistry extractorRegistry;
    private final Sha256Hasher sha256Hasher;
    private final KnowledgeEventPublisher eventPublisher;

    /**
     * Creates the default knowledge service.
     *
     * @param properties knowledge configuration
     * @param index metadata index
     * @param supportedFileTypes supported file type detector
     * @param extractorRegistry document extractor registry
     * @param sha256Hasher SHA-256 hasher
     * @param eventPublisher knowledge event publisher
     */
    public DefaultKnowledgeService(
            KnowledgeProperties properties,
            KnowledgeIndex index,
            SupportedFileTypes supportedFileTypes,
            DocumentExtractorRegistry extractorRegistry,
            Sha256Hasher sha256Hasher,
            KnowledgeEventPublisher eventPublisher
    ) {
        this.properties = properties;
        this.index = index;
        this.supportedFileTypes = supportedFileTypes;
        this.extractorRegistry = extractorRegistry;
        this.sha256Hasher = sha256Hasher;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Lists indexed knowledge document metadata.
     *
     * @return indexed documents
     */
    @Override
    public List<KnowledgeDocument> listDocuments() {
        return index.list();
    }

    /**
     * Lists current knowledge directories from the filesystem.
     *
     * @return relative directory paths
     */
    @Override
    public List<String> listDirectories() {
        Path root = rootPath();
        ensureRootExists(root);
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isDirectory)
                    .filter(path -> !path.equals(root))
                    .filter(path -> !isWorkspaceInternal(path))
                    .map(this::relativePath)
                    .sorted()
                    .toList();
        } catch (IOException exception) {
            throw new KnowledgeException("Failed to list knowledge directories from " + root, exception);
        }
    }

    /**
     * Finds a document by identifier.
     *
     * @param id document identifier
     * @return document metadata, when present
     */
    @Override
    public Optional<KnowledgeDocument> getDocument(UUID id) {
        return index.findById(id);
    }

    /**
     * Rebuilds the metadata index from the configured knowledge root.
     *
     * @return indexed documents
     */
    @Override
    public List<KnowledgeDocument> reindex() {
        Path root = rootPath();
        ensureRootExists(root);
        Map<String, KnowledgeDocument> previousDocuments = index.list().stream()
                .collect(Collectors.toMap(KnowledgeDocument::relativePath, Function.identity()));
        index.clear();

        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> !isWorkspaceInternal(path))
                    .filter(supportedFileTypes::supports)
                    .forEach(path -> indexFile(path, DocumentStatus.INDEXED, previousDocuments));
            eventPublisher.publish(KnowledgeEvent.indexCompleted());
            LOGGER.info("[JARVIS] Knowledge index completed documents={}", index.list().size());
            return index.list();
        } catch (IOException exception) {
            throw new KnowledgeException("Failed to rebuild knowledge index from " + root, exception);
        }
    }

    /**
     * Adds or updates one file in the metadata index.
     *
     * @param path file path
     * @param status status to assign
     * @return indexed document, when supported and present
     */
    @Override
    public Optional<KnowledgeDocument> indexFile(Path path, DocumentStatus status) {
        Path normalizedPath = path.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalizedPath) || isWorkspaceInternal(normalizedPath) || !supportedFileTypes.supports(normalizedPath)) {
            return Optional.empty();
        }
        KnowledgeDocument document = indexFile(normalizedPath, status, Map.of());
        return Optional.of(document);
    }

    /**
     * Removes one file from the metadata index.
     *
     * @param path file path
     * @return removed document, when present
     */
    @Override
    public Optional<KnowledgeDocument> removeFile(Path path) {
        String relativePath = relativePath(path.toAbsolutePath().normalize());
        Optional<KnowledgeDocument> removed = index.removeByRelativePath(relativePath);
        removed.ifPresent(document -> {
            eventPublisher.publish(KnowledgeEvent.document(
                    KnowledgeEventType.DOCUMENT_REMOVED,
                    document.id(),
                    document.relativePath()));
            LOGGER.info("[JARVIS] Knowledge document removed path={}", document.relativePath());
        });
        return removed;
    }

    /**
     * Resolves the configured knowledge root.
     *
     * @return absolute normalized root path
     */
    public Path rootPath() {
        return Paths.get(properties.root()).toAbsolutePath().normalize();
    }

    private KnowledgeDocument indexFile(
            Path path,
            DocumentStatus status,
            Map<String, KnowledgeDocument> previousDocuments
    ) {
        try {
            String relativePath = relativePath(path);
            Optional<KnowledgeDocument> existing = index.findByRelativePath(relativePath);
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
            String sha256 = sha256Hasher.hash(path);
            if (existing.isPresent() && existing.get().sha256().equals(sha256)) {
                LOGGER.info("[KNOWLEDGE_WATCHER] DOCUMENT_INDEXING_SKIPPED path={} hashChanged=false", relativePath);
                return existing.get();
            }
            LOGGER.info("[KNOWLEDGE_WATCHER] DOCUMENT_INDEXING_STARTED path={} hashChanged={}", relativePath, existing.isEmpty());
            String extension = supportedFileTypes.extension(path);
            String preview = preview(path, extension);
            KnowledgeDocument previousDocument = previousDocuments.get(relativePath);
            KnowledgeDocument existingDocument = existing.orElse(null);
            UUID id = Optional.ofNullable(existingDocument)
                    .map(KnowledgeDocument::id)
                    .or(() -> Optional.ofNullable(previousDocument).map(KnowledgeDocument::id))
                    .orElseGet(UUID::randomUUID);

            KnowledgeDocument document = new KnowledgeDocument(
                    id,
                    title(path),
                    relativePath,
                    category(relativePath),
                    extension,
                    attributes.size(),
                    attributes.creationTime().toInstant(),
                    attributes.lastModifiedTime().toInstant(),
                    sha256,
                    preview,
                    status
            );
            index.upsert(document);
            publishDocumentEvent(document, status);
            LOGGER.info("[KNOWLEDGE_WATCHER] DOCUMENT_INDEXING_FINISHED path={} contentHash={}", relativePath, sha256);
            return document;
        } catch (IOException exception) {
            throw new KnowledgeException("Failed to index knowledge document " + path, exception);
        }
    }

    private String preview(Path path, String extension) {
        return extractorRegistry.find(extension)
                .map(extractor -> extractPreview(extractor, path, properties.previewLength()))
                .orElse("");
    }

    private String extractPreview(DocumentExtractor extractor, Path path, int previewLength) {
        try {
            String preview = extractor.preview(path, previewLength);
            return preview.length() <= previewLength ? preview : preview.substring(0, previewLength);
        } catch (RuntimeException exception) {
            LOGGER.warn("[JARVIS] Knowledge preview extraction failed path={} error={}", path, exception.getMessage());
            return "Unsupported yet";
        }
    }

    private void publishDocumentEvent(KnowledgeDocument document, DocumentStatus status) {
        KnowledgeEventType eventType = switch (status) {
            case NEW -> KnowledgeEventType.DOCUMENT_ADDED;
            case UPDATED -> KnowledgeEventType.DOCUMENT_UPDATED;
            case REMOVED -> KnowledgeEventType.DOCUMENT_REMOVED;
            case INDEXED -> KnowledgeEventType.DOCUMENT_ADDED;
            case ERROR -> KnowledgeEventType.DOCUMENT_UPDATED;
        };
        eventPublisher.publish(KnowledgeEvent.document(eventType, document.id(), document.relativePath()));
        LOGGER.info("[JARVIS] Knowledge document indexed path={} status={} size={} sha256={}",
                document.relativePath(),
                document.status(),
                document.fileSize(),
                document.sha256());
    }

    private void ensureRootExists(Path root) {
        try {
            Files.createDirectories(root);
        } catch (IOException exception) {
            throw new KnowledgeException("Failed to create knowledge root " + root, exception);
        }
    }

    private String relativePath(Path path) {
        return rootPath().relativize(path).toString().replace('\\', '/');
    }

    private String title(Path path) {
        String fileName = path.getFileName().toString();
        int index = fileName.lastIndexOf('.');
        return index > 0 ? fileName.substring(0, index) : fileName;
    }

    private String category(String relativePath) {
        String normalizedPath = relativePath.replace('\\', '/');
        int separator = normalizedPath.indexOf('/');
        return separator > 0 ? normalizedPath.substring(0, separator) : "";
    }

    private boolean isWorkspaceInternal(Path path) {
        Path root = rootPath();
        Path normalizedPath = path.toAbsolutePath().normalize();
        if (!normalizedPath.startsWith(root)) {
            return false;
        }
        Path relative = root.relativize(normalizedPath);
        for (Path part : relative) {
            String name = part.toString();
            if (name.equals(".history") || name.equals(".drafts")) {
                return true;
            }
        }
        return false;
    }

}
