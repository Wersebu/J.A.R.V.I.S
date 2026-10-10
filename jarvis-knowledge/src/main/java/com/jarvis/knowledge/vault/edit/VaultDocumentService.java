package com.jarvis.knowledge.vault.edit;

import com.jarvis.knowledge.KnowledgeDocument;
import com.jarvis.knowledge.KnowledgeDocumentIds;
import com.jarvis.knowledge.KnowledgeIndex;
import com.jarvis.knowledge.KnowledgeService;
import com.jarvis.knowledge.vault.KnowledgeVaultService;
import com.jarvis.knowledge.vault.SecretScanner;
import com.jarvis.knowledge.vault.VaultPathPolicy;
import com.jarvis.knowledge.vault.index.StoredDocument;
import com.jarvis.knowledge.vault.index.VaultIndexer;
import com.jarvis.knowledge.vault.note.VaultLinkResolver;
import com.jarvis.knowledge.vault.note.VaultMarkdownParser;
import com.jarvis.knowledge.vault.note.VaultNote;
import com.jarvis.knowledge.workspace.KnowledgeDraft;
import com.jarvis.knowledge.workspace.KnowledgeHistoryStore;
import com.jarvis.knowledge.workspace.KnowledgeVersion;
import com.jarvis.knowledge.workspace.KnowledgeWorkspaceAuditContext;
import com.jarvis.knowledge.workspace.KnowledgeWorkspaceProperties;
import com.jarvis.knowledge.workspace.KnowledgeWorkspaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * User-facing vault operations for the Windows "Pamięć i wiedza" window: browse, read, create,
 * edit, rename/move and restore versions.
 *
 * <p>These edits are authored by the human user (like edits made in Obsidian), so they are applied
 * directly instead of becoming AI drafts. Every overwrite is guarded by the version token the
 * client read ({@code sha256} of the file bytes): if the file changed in the meantime - for example
 * in Obsidian - the write is rejected with {@link VaultEditException.Reason#CONFLICT} and the
 * current content, and nothing is overwritten. The previous content is saved to the shared
 * version history before every change.
 */
@Service
public class VaultDocumentService {

    private static final Logger LOGGER = LoggerFactory.getLogger(VaultDocumentService.class);
    private static final String USER_AUTHOR = "USER";

    private final VaultPathPolicy policy;
    private final KnowledgeService knowledgeService;
    private final KnowledgeIndex knowledgeIndex;
    private final KnowledgeWorkspaceProperties workspaceProperties;
    private final KnowledgeVaultService vaultService;
    private final ObjectProvider<KnowledgeWorkspaceService> workspaceService;
    private final VaultMarkdownParser parser = new VaultMarkdownParser();
    private final Object writeLock = new Object();

    /**
     * Creates the service.
     *
     * @param policy path policy
     * @param knowledgeService legacy metadata index service (refreshed after edits)
     * @param knowledgeIndex legacy metadata index (history ids)
     * @param workspaceProperties workspace properties (history directory)
     * @param vaultService vault service
     * @param workspaceService workspace service (pending drafts)
     */
    public VaultDocumentService(VaultPathPolicy policy, KnowledgeService knowledgeService, KnowledgeIndex knowledgeIndex,
                                KnowledgeWorkspaceProperties workspaceProperties, KnowledgeVaultService vaultService,
                                ObjectProvider<KnowledgeWorkspaceService> workspaceService) {
        this.policy = policy;
        this.knowledgeService = knowledgeService;
        this.knowledgeIndex = knowledgeIndex;
        this.workspaceProperties = workspaceProperties;
        this.vaultService = vaultService;
        this.workspaceService = workspaceService;
    }

    /**
     * Lists folders, notes and attachments.
     *
     * @return root node
     */
    public VaultTreeNode tree() {
        Path root = policy.root();
        try {
            Files.createDirectories(root);
        } catch (IOException exception) {
            throw new VaultEditException(VaultEditException.Reason.INVALID, "Vault root cannot be created: " + root, Map.of());
        }
        return node(root);
    }

    /**
     * Reads a document with its metadata and version token.
     *
     * @param path relative path
     * @return document view
     */
    public VaultDocumentView read(String path) {
        String cleaned = checkedPath(path);
        Path file = policy.resolve(cleaned);
        if (!Files.isRegularFile(file) || !policy.isSafeRegularFile(file)) {
            throw new VaultEditException(VaultEditException.Reason.NOT_FOUND, "Document not found: " + cleaned, Map.of());
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
            String version = VaultIndexer.sha256(bytes);
            boolean note = policy.isNote(cleaned);
            Optional<StoredDocument> indexed = vaultService.active() ? vaultService.indexedDocument(cleaned) : Optional.empty();
            Map<String, Object> index = new LinkedHashMap<>();
            index.put("mode", vaultService.properties().mode().name());
            indexed.ifPresentOrElse(document -> {
                index.put("state", document.state());
                index.put("error", document.error() == null ? "" : document.error());
                index.put("indexedVersion", document.contentHash());
                index.put("upToDate", version.equals(document.contentHash()));
                index.put("documentId", document.documentId());
                index.put("workflow", document.workflow());
            }, () -> index.put("state", vaultService.active() ? (note ? "PENDING" : "ATTACHMENT") : "LEGACY"));
            List<String> backlinks = vaultService.active() ? backlinks(cleaned) : List.of();
            String legacyId = legacyId(cleaned, file).toString();
            if (!note) {
                return new VaultDocumentView(cleaned, file.getFileName().toString(), "", version, attributes.size(),
                        attributes.lastModifiedTime().toInstant().toString(), VaultMarkdownParser.fileTitle(cleaned), "", "attachment", "",
                        List.of(), "", "", "", Map.of(), "", List.of(), backlinks, index, legacyId, List.of(), true, false, "");
            }
            String content = new String(bytes, StandardCharsets.UTF_8);
            VaultNote parsed = parser.parse(cleaned, content);
            VaultLinkResolver resolver = new VaultLinkResolver(allFiles());
            List<VaultDocumentView.LinkView> links = parsed.links().stream()
                    .map(link -> {
                        String resolved = resolver.resolve(cleaned, link).orElse("");
                        return new VaultDocumentView.LinkView(link.kind(), link.target(), link.anchor(), link.label(), resolved,
                                !resolved.isEmpty(), link.line());
                    })
                    .toList();
            String warning = SecretScanner.detect(content).map(kind -> "Looks like it contains a credential (" + kind
                    + "); it is not indexed or given to the model.").orElse("");
            return new VaultDocumentView(cleaned, file.getFileName().toString(), content, version, attributes.size(),
                    attributes.lastModifiedTime().toInstant().toString(), parsed.title(), parsed.frontmatterId(), parsed.type(),
                    parsed.status(), parsed.tags(), parsed.project(), parsed.version(), parsed.updated(), parsed.frontmatter(),
                    parsed.frontmatterError(), links, backlinks, index, legacyId, drafts(cleaned), false, true, warning);
        } catch (IOException exception) {
            throw new VaultEditException(VaultEditException.Reason.NOT_FOUND, "Failed to read " + cleaned + ": " + exception.getMessage(), Map.of());
        }
    }

    /**
     * Overwrites a note if it still has the version the client read.
     *
     * @param path relative path
     * @param content new content
     * @param expectedVersion version token from {@link #read(String)}
     * @return result with the new version token
     */
    public VaultWriteResult save(String path, String content, String expectedVersion) {
        String cleaned = checkedNotePath(path);
        Path file = policy.resolve(cleaned);
        if (expectedVersion == null || expectedVersion.isBlank()) {
            throw new VaultEditException(VaultEditException.Reason.INVALID, "expectedVersion is required to overwrite a document", Map.of());
        }
        synchronized (writeLock) {
            String current = currentVersion(file, cleaned);
            if (!current.equals(expectedVersion)) {
                throw conflict(cleaned, file, current);
            }
            String versionId = saveHistory(cleaned, file, "Edited by the user in Jarvis");
            writeAtomically(file, content == null ? "" : content);
            refresh(cleaned, file);
            return new VaultWriteResult(cleaned, versionOf(file), versionId, "Saved");
        }
    }

    /**
     * Creates a new note; fails when the path already exists.
     *
     * @param path relative path ({@code .md} is added when there is no extension)
     * @param content initial content (blank creates a title heading)
     * @return result
     */
    public VaultWriteResult create(String path, String content) {
        String cleaned = checkedPath(path);
        if (VaultPathPolicy.extension(cleaned).isEmpty()) {
            cleaned = cleaned + ".md";
        }
        if (!policy.isNote(cleaned)) {
            throw new VaultEditException(VaultEditException.Reason.INVALID, "Only .md and .txt notes can be created here: " + cleaned, Map.of());
        }
        checkedPath(cleaned);
        Path file = policy.resolve(cleaned);
        synchronized (writeLock) {
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new VaultEditException(VaultEditException.Reason.ALREADY_EXISTS, "Already exists: " + cleaned, Map.of("path", cleaned));
            }
            String initial = content == null || content.isBlank() ? "# " + VaultMarkdownParser.fileTitle(cleaned) + "\n" : content;
            try {
                Files.createDirectories(file.getParent());
            } catch (IOException exception) {
                throw new VaultEditException(VaultEditException.Reason.INVALID, "Cannot create folder for " + cleaned, Map.of());
            }
            writeAtomically(file, initial);
            refresh(cleaned, file);
            return new VaultWriteResult(cleaned, versionOf(file), "", "Created");
        }
    }

    /**
     * Creates a folder.
     *
     * @param path relative folder path
     * @return result
     */
    public VaultWriteResult createFolder(String path) {
        String cleaned = checkedPath(path);
        if (cleaned.isEmpty()) {
            throw new VaultEditException(VaultEditException.Reason.INVALID, "Folder path is required", Map.of());
        }
        Path folder = policy.resolve(cleaned);
        synchronized (writeLock) {
            if (Files.exists(folder, LinkOption.NOFOLLOW_LINKS)) {
                throw new VaultEditException(VaultEditException.Reason.ALREADY_EXISTS, "Already exists: " + cleaned, Map.of("path", cleaned));
            }
            try {
                Files.createDirectories(folder);
            } catch (IOException exception) {
                throw new VaultEditException(VaultEditException.Reason.INVALID, "Cannot create folder " + cleaned + ": " + exception.getMessage(), Map.of());
            }
            return new VaultWriteResult(cleaned, "", "", "Folder created");
        }
    }

    /**
     * Renames or moves a note, attachment or folder. Never replaces an existing target.
     *
     * @param from current path
     * @param to new path
     * @param expectedVersion optional version token of a note being moved
     * @return result
     */
    public VaultWriteResult move(String from, String to, String expectedVersion) {
        String source = checkedPath(from);
        String target = checkedPath(to);
        if (source.isEmpty() || target.isEmpty()) {
            throw new VaultEditException(VaultEditException.Reason.INVALID, "Both paths are required", Map.of());
        }
        Path sourcePath = policy.resolve(source);
        Path targetPath = policy.resolve(target);
        synchronized (writeLock) {
            if (!Files.exists(sourcePath, LinkOption.NOFOLLOW_LINKS)) {
                throw new VaultEditException(VaultEditException.Reason.NOT_FOUND, "Not found: " + source, Map.of());
            }
            if (Files.exists(targetPath, LinkOption.NOFOLLOW_LINKS) && !sameFileDifferentCase(sourcePath, targetPath)) {
                throw new VaultEditException(VaultEditException.Reason.ALREADY_EXISTS, "Target already exists: " + target, Map.of("path", target));
            }
            boolean directory = Files.isDirectory(sourcePath, LinkOption.NOFOLLOW_LINKS);
            if (directory && targetPath.startsWith(sourcePath)) {
                throw new VaultEditException(VaultEditException.Reason.INVALID, "A folder cannot be moved into itself", Map.of());
            }
            if (!directory && expectedVersion != null && !expectedVersion.isBlank()) {
                String current = currentVersion(sourcePath, source);
                if (!current.equals(expectedVersion)) {
                    throw conflict(source, sourcePath, current);
                }
            }
            try {
                if (!directory) {
                    saveHistory(source, sourcePath, "Moved by the user to " + target);
                }
                Files.createDirectories(targetPath.getParent());
                Files.move(sourcePath, targetPath);
            } catch (IOException exception) {
                throw new VaultEditException(VaultEditException.Reason.INVALID, "Move failed: " + exception.getMessage(), Map.of());
            }
            knowledgeService.reindex();
            vaultService.onKnowledgeChanged(policy.root());
            LOGGER.info("[VAULT] User moved {} -> {}", source, target);
            return new VaultWriteResult(target, directory ? "" : versionOf(targetPath), "", "Moved");
        }
    }

    /**
     * Lists stored versions of a note.
     *
     * @param path relative path
     * @return versions, newest first
     */
    public List<KnowledgeVersion> history(String path) {
        String cleaned = checkedNotePath(path);
        Path file = policy.resolve(cleaned);
        return historyStore().list(legacyId(cleaned, file));
    }

    /**
     * Reads the content of a stored version.
     *
     * @param path relative path
     * @param versionId version id
     * @return content
     */
    public String versionContent(String path, String versionId) {
        String cleaned = checkedNotePath(path);
        Path file = policy.resolve(cleaned);
        return historyStore().content(legacyId(cleaned, file), versionId)
                .orElseThrow(() -> new VaultEditException(VaultEditException.Reason.NOT_FOUND, "Version not found: " + versionId, Map.of()));
    }

    /**
     * Restores a stored version (the current content is saved as a new version first).
     *
     * @param path relative path
     * @param versionId version to restore
     * @param expectedVersion version token of the current file
     * @return result
     */
    public VaultWriteResult restore(String path, String versionId, String expectedVersion) {
        String content = versionContent(path, versionId);
        String cleaned = checkedNotePath(path);
        Path file = policy.resolve(cleaned);
        synchronized (writeLock) {
            String current = currentVersion(file, cleaned);
            if (expectedVersion == null || !current.equals(expectedVersion)) {
                throw conflict(cleaned, file, current);
            }
            String saved = saveHistory(cleaned, file, "Before restoring version " + versionId);
            writeAtomically(file, content);
            refresh(cleaned, file);
            return new VaultWriteResult(cleaned, versionOf(file), saved, "Restored version " + versionId);
        }
    }

    private VaultTreeNode node(Path path) {
        boolean root = path.equals(policy.root());
        String relative = root ? "" : policy.relativize(path);
        try {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isDirectory()) {
                List<VaultTreeNode> children = new ArrayList<>();
                try (Stream<Path> entries = Files.list(path)) {
                    for (Path child : entries.sorted(Comparator.comparing(entry -> entry.getFileName().toString().toLowerCase(Locale.ROOT))).toList()) {
                        if (policy.isExcluded(child)) {
                            continue;
                        }
                        if (Files.isSymbolicLink(child) && !policy.isSafeRegularFile(child)) {
                            continue;
                        }
                        children.add(node(child));
                    }
                }
                children.sort(Comparator.comparing((VaultTreeNode node) -> !"folder".equals(node.kind()))
                        .thenComparing(node -> node.name().toLowerCase(Locale.ROOT)));
                return new VaultTreeNode(root ? "Vault" : path.getFileName().toString(), relative, "folder", 0, "", "", "", "", "", false, children);
            }
            boolean note = policy.isNote(relative);
            Optional<StoredDocument> indexed = note && vaultService.active() ? vaultService.indexedDocument(relative) : Optional.empty();
            return new VaultTreeNode(path.getFileName().toString(), relative, note ? "note" : "attachment", attributes.size(),
                    attributes.lastModifiedTime().toInstant().toString(),
                    indexed.map(StoredDocument::documentId).orElse(""),
                    indexed.map(StoredDocument::type).orElse(""),
                    indexed.map(StoredDocument::status).orElse(""),
                    indexed.map(StoredDocument::state).orElse(note ? (vaultService.active() ? "PENDING" : "LEGACY") : ""),
                    indexed.map(StoredDocument::workflow).orElse(false),
                    List.of());
        } catch (IOException exception) {
            return new VaultTreeNode(path.getFileName() == null ? "" : path.getFileName().toString(), relative, "error", 0, "", "", "", "",
                    "ERROR", false, List.of());
        }
    }

    private List<String> backlinks(String path) {
        List<String> sources = new ArrayList<>();
        String needle = "|" + path;
        for (StoredDocument document : documentsSnapshot()) {
            if (document.links().stream().anyMatch(link -> link.endsWith(needle))) {
                sources.add(document.path());
            }
        }
        return sources;
    }

    private List<StoredDocument> documentsSnapshot() {
        return vaultService.indexedDocuments();
    }

    private List<String> allFiles() {
        List<String> files = new ArrayList<>();
        try {
            Files.walkFileTree(policy.root(), new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                    return !directory.equals(policy.root()) && policy.isExcluded(directory) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (!policy.isExcluded(file)) {
                        files.add(policy.relativize(file));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exception) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException exception) {
            LOGGER.debug("[VAULT] File listing failed: {}", exception.getMessage());
        }
        return files;
    }

    private List<KnowledgeDraft> drafts(String path) {
        KnowledgeWorkspaceService workspace = workspaceService.getIfAvailable();
        if (workspace == null) {
            return List.of();
        }
        return workspace.drafts().stream().filter(draft -> path.equals(draft.path())).toList();
    }

    private String checkedPath(String path) {
        String cleaned;
        try {
            cleaned = VaultPathPolicy.clean(path);
            policy.resolve(cleaned);
        } catch (VaultPathPolicy.VaultPathException exception) {
            throw new VaultEditException(VaultEditException.Reason.INVALID, exception.getMessage(), Map.of());
        }
        if (!cleaned.isEmpty() && policy.isExcluded(cleaned)) {
            throw new VaultEditException(VaultEditException.Reason.EXCLUDED, "Path is excluded from the vault (Obsidian/Git internals, "
                    + "temporary or backup files, secrets, system prompt): " + cleaned, Map.of());
        }
        return cleaned;
    }

    private String checkedNotePath(String path) {
        String cleaned = checkedPath(path);
        if (!policy.isNote(cleaned)) {
            throw new VaultEditException(VaultEditException.Reason.INVALID, "Not a Markdown or text note: " + cleaned, Map.of());
        }
        return cleaned;
    }

    private String currentVersion(Path file, String path) {
        if (!Files.isRegularFile(file) || !policy.isSafeRegularFile(file)) {
            throw new VaultEditException(VaultEditException.Reason.CONFLICT, "The document no longer exists (deleted or moved meanwhile): " + path,
                    Map.of("path", path, "currentVersion", "", "currentContent", "", "deleted", true));
        }
        return versionOf(file);
    }

    private VaultEditException conflict(String path, Path file, String currentVersion) {
        String content;
        try {
            content = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            content = "";
        }
        return new VaultEditException(VaultEditException.Reason.CONFLICT,
                "The document was changed since you opened it (for example in Obsidian). Nothing was overwritten.",
                Map.of("path", path, "currentVersion", currentVersion, "currentContent", content, "deleted", false));
    }

    private String versionOf(Path file) {
        try {
            return VaultIndexer.sha256(Files.readAllBytes(file));
        } catch (IOException exception) {
            return "";
        }
    }

    private String saveHistory(String path, Path file, String summary) {
        try {
            return historyStore().save(legacyId(path, file), path, file, USER_AUTHOR, summary,
                    new KnowledgeWorkspaceAuditContext.Audit("", "", "vault.editor", summary, "")).orElse("");
        } catch (IOException exception) {
            throw new VaultEditException(VaultEditException.Reason.INVALID, "Could not save the previous version; nothing was changed: "
                    + exception.getMessage(), Map.of());
        }
    }

    private void writeAtomically(Path file, String content) {
        Path temp = file.resolveSibling(".~jarvis-" + UUID.randomUUID() + ".tmp");
        try {
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // best effort cleanup
            }
            throw new VaultEditException(VaultEditException.Reason.INVALID, "Write failed: " + exception.getMessage(), Map.of());
        }
    }

    private void refresh(String path, Path file) {
        knowledgeService.indexFile(file, com.jarvis.knowledge.DocumentStatus.UPDATED);
        vaultService.onKnowledgeChanged(file);
        LOGGER.info("[VAULT] User edit applied path={}", path);
    }

    private UUID legacyId(String path, Path file) {
        return knowledgeIndex.findByRelativePath(path)
                .map(KnowledgeDocument::id)
                .orElseGet(() -> KnowledgeDocumentIds.of(path, KnowledgeDocumentIds.frontmatterId(file)));
    }

    private KnowledgeHistoryStore historyStore() {
        return new KnowledgeHistoryStore(policy.root(), policy.root().resolve(workspaceProperties.historyDirectory()));
    }

    private boolean sameFileDifferentCase(Path source, Path target) {
        try {
            return !source.toString().equals(target.toString()) && source.toString().equalsIgnoreCase(target.toString())
                    && Files.isSameFile(source, target);
        } catch (IOException exception) {
            return false;
        }
    }
}
