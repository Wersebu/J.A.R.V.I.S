package com.jarvis.core.workspace;

import com.jarvis.api.service.TemporaryWorkspaceService;
import com.jarvis.common.dto.AttachmentMetadata;
import com.jarvis.common.dto.AttachmentReference;
import com.jarvis.common.dto.TemporaryWorkspaceMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Stream;

/**
 * Filesystem-backed manager for isolated temporary attachment workspaces.
 */
@Service
public class DefaultTemporaryWorkspaceService implements TemporaryWorkspaceService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultTemporaryWorkspaceService.class);
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
            "txt", "md", "log", "csv", "java", "py", "js", "ts", "html", "css",
            "json", "xml", "yml", "yaml", "properties", "sql", "sh", "ps1"
    );
    private static final String WORKSPACE_FILE = ".workspace.properties";
    private static final String METADATA_DIRECTORY = ".metadata";

    private final TemporaryWorkspaceProperties properties;
    private final ConcurrentHashMap<String, ReentrantReadWriteLock> locks;

    /**
     * Creates the temporary workspace manager.
     *
     * @param properties workspace properties
     */
    public DefaultTemporaryWorkspaceService(TemporaryWorkspaceProperties properties) {
        this.properties = properties;
        this.locks = new ConcurrentHashMap<>();
    }

    @Override
    public TemporaryWorkspaceMetadata createWorkspace(String conversationId) {
        String workspaceId = UUID.randomUUID().toString();
        ReentrantReadWriteLock lock = lock(workspaceId);
        lock.writeLock().lock();
        try {
            Path workspace = workspacePath(workspaceId);
            Files.createDirectories(workspace.resolve("input").resolve(METADATA_DIRECTORY));
            Files.createDirectories(workspace.resolve("extracted"));
            Files.createDirectories(workspace.resolve("output"));
            Instant now = Instant.now();
            saveWorkspaceProperties(workspace, workspaceId, conversationId, now, now);
            LOGGER.info("[WORKSPACE] created id={} conversationId={}", workspaceId, conversationId);
            return metadata(workspaceId);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not create temporary workspace", exception);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public List<AttachmentMetadata> storeInput(String workspaceId, String conversationId, List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("No files uploaded.");
        }
        validateWorkspaceId(workspaceId);
        ReentrantReadWriteLock lock = lock(workspaceId);
        lock.writeLock().lock();
        try {
            Path workspace = ensureWorkspace(workspaceId, conversationId);
            List<AttachmentMetadata> existing = attachments(workspaceId);
            if (existing.size() + files.size() > properties.getMaxFilesPerWorkspace()) {
                throw new IllegalArgumentException("Too many files in temporary workspace.");
            }
            long incomingSize = files.stream().mapToLong(MultipartFile::getSize).sum();
            ensureStorageAvailable(workspace, incomingSize);
            List<AttachmentMetadata> stored = new java.util.ArrayList<>();
            for (MultipartFile file : files) {
                stored.add(storeOne(workspaceId, workspace, file));
            }
            touch(workspaceId);
            return List.copyOf(stored);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not store attachment.", exception);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public ReadableAttachment readAttachment(AttachmentReference reference) {
        if (reference == null) {
            throw new IllegalArgumentException("Attachment reference is required.");
        }
        validateWorkspaceId(reference.workspaceId());
        validateWorkspaceId(reference.attachmentId());
        ReentrantReadWriteLock lock = lock(reference.workspaceId());
        lock.readLock().lock();
        try {
            AttachmentMetadata metadata = attachment(reference.workspaceId(), reference.attachmentId());
            Path file = safeResolve(workspacePath(reference.workspaceId()).resolve("input"), metadata.storedFileName());
            String content = Files.readString(file, StandardCharsets.UTF_8);
            touch(reference.workspaceId());
            return new ReadableAttachment(metadata, content);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read attachment.", exception);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public TemporaryWorkspaceMetadata metadata(String workspaceId) {
        validateWorkspaceId(workspaceId);
        try {
            Path workspace = workspacePath(workspaceId);
            Properties props = loadWorkspaceProperties(workspace);
            return new TemporaryWorkspaceMetadata(
                    workspaceId,
                    props.getProperty("conversationId", ""),
                    Instant.parse(props.getProperty("createdAt")),
                    Instant.parse(props.getProperty("lastAccessAt")),
                    calculateSize(workspace),
                    attachments(workspaceId)
            );
        } catch (IOException exception) {
            throw new IllegalArgumentException("Temporary workspace not found: " + workspaceId, exception);
        }
    }

    @Override
    public void deleteWorkspace(String workspaceId) {
        validateWorkspaceId(workspaceId);
        ReentrantReadWriteLock lock = lock(workspaceId);
        lock.writeLock().lock();
        try {
            deleteTree(workspacePath(workspaceId));
            locks.remove(workspaceId);
            LOGGER.info("[WORKSPACE] deleted id={}", workspaceId);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not delete temporary workspace.", exception);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public int cleanupExpired() {
        try {
            Files.createDirectories(root());
            Instant cutoff = Instant.now().minus(properties.getTtl());
            int removed = 0;
            try (Stream<Path> paths = Files.list(root())) {
                for (Path workspace : paths.filter(Files::isDirectory).toList()) {
                    String workspaceId = workspace.getFileName().toString();
                    ReentrantReadWriteLock lock = lock(workspaceId);
                    if (!lock.writeLock().tryLock()) {
                        continue;
                    }
                    try {
                        Properties props = loadWorkspaceProperties(workspace);
                        Instant lastAccess = Instant.parse(props.getProperty("lastAccessAt"));
                        if (lastAccess.isBefore(cutoff)) {
                            long size = calculateSize(workspace);
                            deleteTree(workspace);
                            locks.remove(workspaceId);
                            removed++;
                            LOGGER.info("[WORKSPACE] cleanup id={} size={}", workspaceId, size);
                        }
                    } catch (IOException | IllegalArgumentException exception) {
                        LOGGER.warn("[WORKSPACE] cleanup skipped id={} reason={}", workspaceId, exception.getMessage());
                    } finally {
                        lock.writeLock().unlock();
                    }
                }
            }
            return removed;
        } catch (IOException exception) {
            LOGGER.warn("[WORKSPACE] cleanup failed reason={}", exception.getMessage());
            return 0;
        }
    }

    private AttachmentMetadata storeOne(String workspaceId, Path workspace, MultipartFile file) throws IOException {
        String originalName = sanitizeFileName(file.getOriginalFilename());
        String extension = extension(originalName);
        if (!SUPPORTED_EXTENSIONS.contains(extension)) {
            LOGGER.info("[ATTACHMENT] rejected reason=unsupported_type name={}", originalName);
            throw new IllegalArgumentException("Unsupported file type: " + originalName);
        }
        if (file.getSize() > properties.getMaxFileSizeBytes()) {
            LOGGER.info("[ATTACHMENT] rejected reason=size_limit name={} size={}", originalName, file.getSize());
            throw new IllegalArgumentException("File is too large: " + originalName);
        }
        byte[] bytes = file.getBytes();
        validateText(bytes, originalName);
        String attachmentId = UUID.randomUUID().toString();
        String storedName = attachmentId + "." + extension;
        Path target = safeResolve(workspace.resolve("input"), storedName);
        Path partial = safeResolve(workspace.resolve("input"), storedName + ".partial");
        try {
            Files.write(partial, bytes);
            Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            Files.deleteIfExists(partial);
            throw exception;
        }
        AttachmentMetadata metadata = new AttachmentMetadata(
                attachmentId,
                workspaceId,
                originalName,
                storedName,
                extension,
                file.getContentType() == null ? "text/plain" : file.getContentType(),
                bytes.length,
                Instant.now()
        );
        saveAttachmentMetadata(workspace, metadata);
        LOGGER.info("[ATTACHMENT] uploaded workspace={} name={} size={}", workspaceId, originalName, bytes.length);
        return metadata;
    }

    private void validateText(byte[] bytes, String originalName) {
        if (bytes.length == 0) {
            return;
        }
        for (byte value : bytes) {
            if (value == 0) {
                throw new IllegalArgumentException("Unsupported binary file: " + originalName);
            }
        }
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("File is not valid UTF-8 text: " + originalName, exception);
        }
    }

    private void ensureStorageAvailable(Path workspace, long incomingSize) throws IOException {
        long workspaceSize = calculateSize(workspace);
        if (workspaceSize + incomingSize > properties.getMaxWorkspaceSizeBytes()) {
            throw new IllegalArgumentException("Temporary workspace size limit exceeded.");
        }
        cleanupExpired();
        if (calculateSize(root()) + incomingSize > properties.getMaxTotalSizeBytes()) {
            throw new IllegalStateException("Temporary storage limit exceeded.");
        }
        if (root().toFile().getUsableSpace() - incomingSize < properties.getMinimumFreeDiskSpaceBytes()) {
            throw new IllegalStateException("Not enough free disk space for temporary upload.");
        }
    }

    private Path ensureWorkspace(String workspaceId, String conversationId) throws IOException {
        Path workspace = workspacePath(workspaceId);
        if (!Files.exists(workspace.resolve(WORKSPACE_FILE))) {
            Files.createDirectories(workspace.resolve("input").resolve(METADATA_DIRECTORY));
            Files.createDirectories(workspace.resolve("extracted"));
            Files.createDirectories(workspace.resolve("output"));
            Instant now = Instant.now();
            saveWorkspaceProperties(workspace, workspaceId, conversationId, now, now);
        }
        return workspace;
    }

    private void touch(String workspaceId) throws IOException {
        Path workspace = workspacePath(workspaceId);
        Properties props = loadWorkspaceProperties(workspace);
        saveWorkspaceProperties(
                workspace,
                workspaceId,
                props.getProperty("conversationId", ""),
                Instant.parse(props.getProperty("createdAt")),
                Instant.now()
        );
        LOGGER.debug("[WORKSPACE] touched id={}", workspaceId);
    }

    private List<AttachmentMetadata> attachments(String workspaceId) throws IOException {
        Path metadataDirectory = workspacePath(workspaceId).resolve("input").resolve(METADATA_DIRECTORY);
        if (!Files.exists(metadataDirectory)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.list(metadataDirectory)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".properties"))
                    .sorted()
                    .map(this::loadAttachmentMetadata)
                    .toList();
        }
    }

    private AttachmentMetadata attachment(String workspaceId, String attachmentId) throws IOException {
        Path path = safeResolve(workspacePath(workspaceId).resolve("input").resolve(METADATA_DIRECTORY), attachmentId + ".properties");
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("Temporary attachment not found: " + attachmentId);
        }
        return loadAttachmentMetadata(path);
    }

    private AttachmentMetadata loadAttachmentMetadata(Path path) {
        try (var input = Files.newInputStream(path)) {
            Properties props = new Properties();
            props.load(input);
            return new AttachmentMetadata(
                    props.getProperty("attachmentId"),
                    props.getProperty("workspaceId"),
                    props.getProperty("originalFileName"),
                    props.getProperty("storedFileName"),
                    props.getProperty("extension"),
                    props.getProperty("contentType"),
                    Long.parseLong(props.getProperty("size")),
                    Instant.parse(props.getProperty("createdAt"))
            );
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read attachment metadata.", exception);
        }
    }

    private void saveAttachmentMetadata(Path workspace, AttachmentMetadata metadata) throws IOException {
        Properties props = new Properties();
        props.setProperty("attachmentId", metadata.attachmentId());
        props.setProperty("workspaceId", metadata.workspaceId());
        props.setProperty("originalFileName", metadata.originalFileName());
        props.setProperty("storedFileName", metadata.storedFileName());
        props.setProperty("extension", metadata.extension());
        props.setProperty("contentType", metadata.contentType());
        props.setProperty("size", Long.toString(metadata.size()));
        props.setProperty("createdAt", metadata.createdAt().toString());
        Path metadataDirectory = workspace.resolve("input").resolve(METADATA_DIRECTORY);
        Files.createDirectories(metadataDirectory);
        Path target = safeResolve(metadataDirectory, metadata.attachmentId() + ".properties");
        Path partial = safeResolve(metadataDirectory, metadata.attachmentId() + ".properties.partial");
        try (var output = Files.newOutputStream(partial)) {
            props.store(output, "Jarvis temporary attachment metadata");
        }
        Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private Properties loadWorkspaceProperties(Path workspace) throws IOException {
        Properties props = new Properties();
        try (var input = Files.newInputStream(workspace.resolve(WORKSPACE_FILE))) {
            props.load(input);
        }
        return props;
    }

    private void saveWorkspaceProperties(
            Path workspace,
            String workspaceId,
            String conversationId,
            Instant createdAt,
            Instant lastAccessAt
    ) throws IOException {
        Files.createDirectories(workspace);
        Properties props = new Properties();
        props.setProperty("workspaceId", workspaceId);
        props.setProperty("conversationId", conversationId == null ? "" : conversationId);
        props.setProperty("createdAt", createdAt.toString());
        props.setProperty("lastAccessAt", lastAccessAt.toString());
        Path partial = workspace.resolve(WORKSPACE_FILE + ".partial");
        try (var output = Files.newOutputStream(partial)) {
            props.store(output, "Jarvis temporary workspace metadata");
        }
        Files.move(partial, workspace.resolve(WORKSPACE_FILE), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private Path root() {
        return properties.getRoot().toAbsolutePath().normalize();
    }

    private Path workspacePath(String workspaceId) {
        return safeResolve(root(), workspaceId);
    }

    private Path safeResolve(Path parent, String child) {
        Path base = parent.toAbsolutePath().normalize();
        Path resolved = base.resolve(child).normalize();
        if (!resolved.startsWith(base)) {
            throw new IllegalArgumentException("Invalid temporary workspace path.");
        }
        return resolved;
    }

    private void validateWorkspaceId(String value) {
        if (value == null || !value.matches("[a-zA-Z0-9._-]{1,80}")) {
            throw new IllegalArgumentException("Invalid temporary workspace identifier.");
        }
    }

    private String sanitizeFileName(String original) {
        String candidate = original == null || original.isBlank() ? "attachment.txt" : Path.of(original).getFileName().toString();
        String sanitized = candidate.replace('\\', '_').replace('/', '_').replaceAll("[\\p{Cntrl}]", "_").strip();
        return sanitized.isBlank() ? "attachment.txt" : sanitized;
    }

    private String extension(String fileName) {
        int index = fileName.lastIndexOf('.');
        if (index < 0 || index == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(index + 1).toLowerCase(Locale.ROOT);
    }

    private long calculateSize(Path path) throws IOException {
        if (!Files.exists(path)) {
            return 0L;
        }
        try (Stream<Path> stream = Files.walk(path)) {
            return stream.filter(Files::isRegularFile)
                    .mapToLong(file -> {
                        try {
                            return Files.size(file);
                        } catch (IOException exception) {
                            return 0L;
                        }
                    })
                    .sum();
        }
    }

    private void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(path)) {
            for (Path item : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(item);
            }
        }
    }

    private ReentrantReadWriteLock lock(String workspaceId) {
        return locks.computeIfAbsent(workspaceId, ignored -> new ReentrantReadWriteLock());
    }
}
