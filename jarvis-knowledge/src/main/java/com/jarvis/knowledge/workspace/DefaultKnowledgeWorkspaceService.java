package com.jarvis.knowledge.workspace;

import com.jarvis.common.event.CognitiveEventBus;
import com.jarvis.common.event.CognitiveEventType;
import com.jarvis.knowledge.KnowledgeDocument;
import com.jarvis.knowledge.KnowledgeException;
import com.jarvis.knowledge.KnowledgeIndex;
import com.jarvis.knowledge.KnowledgeProperties;
import com.jarvis.knowledge.KnowledgeService;
import com.jarvis.knowledge.retrieval.KnowledgeRetriever;
import com.jarvis.knowledge.retrieval.RetrievalResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Default safe filesystem-backed implementation of the knowledge workspace tools.
 */
@Service
public class DefaultKnowledgeWorkspaceService implements KnowledgeWorkspaceService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultKnowledgeWorkspaceService.class);
    private static final String ROOT_NODE_ID = "knowledge-root:/";
    private static final String FOLDER_PREFIX = "knowledge-folder:";
    private static final String DOCUMENT_PREFIX = "knowledge-document:";
    private static final String AI_AUTHOR = "AI";

    private final KnowledgeProperties knowledgeProperties;
    private final KnowledgeWorkspaceProperties workspaceProperties;
    private final KnowledgeService knowledgeService;
    private final KnowledgeIndex knowledgeIndex;
    private final KnowledgeRetriever knowledgeRetriever;
    private final CognitiveEventBus cognitiveEventBus;

    /**
     * Creates the workspace service.
     */
    public DefaultKnowledgeWorkspaceService(
            KnowledgeProperties knowledgeProperties,
            KnowledgeWorkspaceProperties workspaceProperties,
            KnowledgeService knowledgeService,
            KnowledgeIndex knowledgeIndex,
            KnowledgeRetriever knowledgeRetriever,
            CognitiveEventBus cognitiveEventBus
    ) {
        this.knowledgeProperties = knowledgeProperties;
        this.workspaceProperties = workspaceProperties;
        this.knowledgeService = knowledgeService;
        this.knowledgeIndex = knowledgeIndex;
        this.knowledgeRetriever = knowledgeRetriever;
        this.cognitiveEventBus = cognitiveEventBus;
    }

    @Override
    public KnowledgeWorkspaceTree list() {
        Path root = rootPath();
        ensureDirectory(root);
        Map<String, KnowledgeDocument> indexedDocuments = new HashMap<>();
        knowledgeIndex.list().forEach(document -> indexedDocuments.put(document.relativePath(), document));
        KnowledgeWorkspaceNode rootNode = buildNode(root, indexedDocuments);
        int documents = count(rootNode, KnowledgeNodeType.DOCUMENT);
        int folders = Math.max(0, count(rootNode, KnowledgeNodeType.FOLDER) - 1);
        return new KnowledgeWorkspaceTree(rootNode, Instant.now(), documents, folders);
    }

    @Override
    public RetrievalResult search(String query) {
        return knowledgeRetriever.retrieve(query);
    }

    @Override
    public KnowledgeToolResult read(UUID documentId) {
        KnowledgeDocument document = document(documentId);
        return read(document.relativePath());
    }

    @Override
    public KnowledgeToolResult read(String logicalPath) {
        Path path = resolveRelative(logicalPath);
        String relativePath = relativePath(path);
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            return result("knowledge.read", true, false, "Document read", nodeId(relativePath),
                    relativePath, Map.of(
                            "documentId", knowledgeIndex.findByRelativePath(relativePath)
                                    .map(document -> document.id().toString())
                                    .orElse(""),
                            "content", content,
                            "characters", content.length()
                    ));
        } catch (IOException exception) {
            throw new KnowledgeException("Failed to read knowledge document " + relativePath, exception);
        }
    }

    @Override
    public KnowledgeToolResult createFolder(String parentId, String name) {
        Path target = resolveRelative(join(parentPath(parentId), safeName(name, false)));
        return writeOperation("knowledge.createFolder", folderNodeId(relativePath(target)), relativePath(target),
                CognitiveEventType.FOLDER_CREATED,
                "Folder created",
                Map.of("name", target.getFileName().toString()),
                () -> Files.createDirectories(target));
    }

    @Override
    public KnowledgeToolResult createDocument(String parentId, String name, String content) {
        String fileName = safeName(name, true);
        Path target = resolveRelative(join(parentPath(parentId), fileName));
        String documentContent = content == null || content.isBlank() ? templateFor(fileName) : content;
        return writeOperation("knowledge.createDocument", nodeId(relativePath(target)), relativePath(target),
                CognitiveEventType.DOCUMENT_CREATED,
                "Document created",
                Map.of("name", fileName, "characters", documentContent.length()),
                () -> {
                    ensureDirectory(target.getParent());
                    Files.writeString(target, withMetadata(fileName, documentContent), StandardCharsets.UTF_8);
                });
    }

    @Override
    public KnowledgeToolResult updateDocument(UUID documentId, String newContent) {
        KnowledgeDocument document = document(documentId);
        Path path = resolveRelative(document.relativePath());
        String content = newContent == null ? "" : newContent;
        return writeOperation("knowledge.updateDocument", nodeId(document.relativePath()), document.relativePath(),
                CognitiveEventType.DOCUMENT_UPDATED,
                "Document updated",
                Map.of("documentId", document.id().toString(), "characters", content.length()),
                () -> {
                    saveVersion(document, "Replace document content");
                    Files.writeString(path, content, StandardCharsets.UTF_8);
                });
    }

    @Override
    public KnowledgeToolResult updateDocument(String logicalPath, String instruction, String text) {
        Path path = resolveRelative(logicalPath);
        String relativePath = relativePath(path);
        String normalizedInstruction = instruction == null ? "" : instruction.trim();
        String payload = text == null ? "" : text.trim();
        return writeOperation("knowledge.updateDocument", nodeId(relativePath), relativePath,
                CognitiveEventType.DOCUMENT_UPDATED,
                "Document updated",
                Map.of(
                        "instruction", normalizedInstruction,
                        "characters", payload.length()
                ),
                () -> {
                    Optional<KnowledgeDocument> document = knowledgeIndex.findByRelativePath(relativePath);
                    if (document.isPresent()) {
                        saveVersion(document.get(), "Instruction update: " + normalizedInstruction);
                    }
                    String current = Files.exists(path) ? Files.readString(path, StandardCharsets.UTF_8) : "";
                    String updated = applyInstruction(current, normalizedInstruction, payload);
                    ensureDirectory(path.getParent());
                    Files.writeString(path, updated, StandardCharsets.UTF_8);
                });
    }

    @Override
    public KnowledgeToolResult appendDocument(UUID documentId, String text) {
        KnowledgeDocument document = document(documentId);
        return appendDocument(document.relativePath(), text);
    }

    @Override
    public KnowledgeToolResult appendDocument(String logicalPath, String text) {
        Path path = resolveRelative(logicalPath);
        String relativePath = relativePath(path);
        String addition = text == null ? "" : text;
        return writeOperation("knowledge.appendDocument", nodeId(relativePath), relativePath,
                CognitiveEventType.DOCUMENT_UPDATED,
                "Document appended",
                Map.of(
                        "documentId", knowledgeIndex.findByRelativePath(relativePath)
                                .map(document -> document.id().toString())
                                .orElse(""),
                        "characters", addition.length()
                ),
                () -> {
                    Optional<KnowledgeDocument> document = knowledgeIndex.findByRelativePath(relativePath);
                    if (document.isPresent()) {
                        saveVersion(document.get(), "Append document content");
                    }
                    ensureDirectory(path.getParent());
                    if (!Files.exists(path)) {
                        Files.writeString(path, withMetadata(path.getFileName().toString(), addition), StandardCharsets.UTF_8);
                        return;
                    }
                    Files.writeString(path, System.lineSeparator() + addition, StandardCharsets.UTF_8,
                            java.nio.file.StandardOpenOption.APPEND);
                });
    }

    @Override
    public KnowledgeToolResult rename(String nodeId, String newName) {
        Path source = resolveNode(nodeId);
        boolean directory = Files.isDirectory(source);
        String safeName = safeName(newName, !directory);
        Path target = source.resolveSibling(safeName);
        return moveLike("knowledge.rename", nodeIdFor(target, directory), relativePath(target), CognitiveEventType.DOCUMENT_RENAMED,
                "Node renamed", source, target, "Rename node");
    }

    @Override
    public KnowledgeToolResult move(String nodeId, String newParent) {
        Path source = resolveNode(nodeId);
        Path parent = resolveNode(newParent);
        if (!Files.isDirectory(parent)) {
            throw new KnowledgeException("Move parent is not a folder: " + newParent);
        }
        boolean directory = Files.isDirectory(source);
        Path target = parent.resolve(source.getFileName()).normalize();
        return moveLike("knowledge.move", nodeIdFor(target, directory), relativePath(target), CognitiveEventType.DOCUMENT_MOVED,
                "Node moved", source, target, "Move node");
    }

    @Override
    public KnowledgeToolResult delete(String nodeId) {
        Path target = resolveNode(nodeId);
        return writeOperation("knowledge.delete", nodeId, relativePath(target),
                CognitiveEventType.DOCUMENT_DELETED,
                "Node deleted",
                Map.of("recursive", Files.isDirectory(target)),
                () -> {
                    saveVersionsForDelete(target);
                    if (Files.isDirectory(target)) {
                        try (Stream<Path> paths = Files.walk(target)) {
                            List<Path> deleteOrder = paths.sorted(Comparator.reverseOrder()).toList();
                            for (Path path : deleteOrder) {
                                Files.deleteIfExists(path);
                            }
                        }
                    } else {
                        Files.deleteIfExists(target);
                    }
                });
    }

    @Override
    public KnowledgeToolResult exists(String path) {
        Path resolved = resolveRelative(path);
        boolean exists = Files.exists(resolved);
        return result("knowledge.exists", true, false, exists ? "Path exists" : "Path does not exist",
                nodeIdFor(resolved), relativePath(resolved), Map.of("exists", exists));
    }

    @Override
    public KnowledgeToolResult index() {
        List<KnowledgeDocument> documents = knowledgeService.reindex();
        publishRefresh(null, null);
        return result("knowledge.index", true, false, "Knowledge index refreshed", null, null, Map.of(
                "documents", documents.size()
        ));
    }

    @Override
    public List<KnowledgeVersion> history(UUID documentId) {
        KnowledgeDocument document = document(documentId);
        Path folder = historyRoot().resolve(document.id().toString());
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.list(folder)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".meta"))
                    .sorted(Comparator.reverseOrder())
                    .map(this::readVersion)
                    .toList();
        } catch (IOException exception) {
            throw new KnowledgeException("Failed to read knowledge history for " + document.id(), exception);
        }
    }

    private KnowledgeToolResult moveLike(
            String tool,
            String resultNodeId,
            String resultPath,
            CognitiveEventType eventType,
            String message,
            Path source,
            Path target,
            String summary
    ) {
        return writeOperation(tool, resultNodeId, resultPath, eventType, message,
                Map.of("source", relativePath(source), "target", relativePath(target)),
                () -> {
                    if (Files.isRegularFile(source)) {
                        Optional<KnowledgeDocument> document = knowledgeIndex.findByRelativePath(relativePath(source));
                        if (document.isPresent()) {
                            saveVersion(document.get(), summary);
                        }
                    } else {
                        saveVersionsForDelete(source);
                    }
                    ensureDirectory(target.getParent());
                    Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
                });
    }

    private KnowledgeToolResult writeOperation(
            String tool,
            String nodeId,
            String path,
            CognitiveEventType eventType,
            String message,
            Map<String, Object> data,
            WorkspaceWrite write
    ) {
        KnowledgeWriteMode mode = workspaceProperties.writeMode();
        if (mode == KnowledgeWriteMode.READ_ONLY) {
            return result(tool, false, false, "Knowledge workspace is read-only", nodeId, path, data);
        }
        if (mode == KnowledgeWriteMode.AUTO_DRAFT) {
            saveDraft(tool, path, data);
            return result(tool, false, true, "Draft created. No filesystem changes applied.", nodeId, path, data);
        }
        try {
            write.apply();
            refreshAfterChange(eventType, message, nodeId, path, data);
            return result(tool, true, false, message, nodeId, path, data);
        } catch (IOException exception) {
            throw new KnowledgeException("Knowledge workspace operation failed: " + tool + " path=" + path, exception);
        }
    }

    private void refreshAfterChange(
            CognitiveEventType eventType,
            String message,
            String nodeId,
            String path,
            Map<String, Object> data
    ) {
        LOGGER.info("[JARVIS][KNOWLEDGE_WORKSPACE] {} path={} nodeId={}", eventType, path, nodeId);
        Map<String, Object> eventData = eventData(path, data);
        cognitiveEventBus.publish(eventType, "OK", message, nodeId, eventData);
        cognitiveEventBus.publishBackground(UUID.randomUUID().toString(), "knowledge-workspace", eventType, "OK",
                message, nodeId, eventData);
        knowledgeService.reindex();
        publishRefresh(nodeId, path);
    }

    private void publishRefresh(String nodeId, String path) {
        Map<String, Object> indexData = eventData(path, Map.of());
        Map<String, Object> graphData = eventData(path, Map.of());
        cognitiveEventBus.publish(CognitiveEventType.INDEX_REFRESH, "OK", "Knowledge index refreshed", nodeId, indexData);
        cognitiveEventBus.publish(CognitiveEventType.GRAPH_REFRESH, "OK", "Knowledge graph refresh requested", nodeId, graphData);
        cognitiveEventBus.publishBackground(UUID.randomUUID().toString(), "knowledge-workspace",
                CognitiveEventType.INDEX_REFRESH, "OK", "Knowledge index refreshed", nodeId, indexData);
        cognitiveEventBus.publishBackground(UUID.randomUUID().toString(), "knowledge-workspace",
                CognitiveEventType.GRAPH_REFRESH, "OK", "Knowledge graph refresh requested", nodeId, graphData);
        LOGGER.info("[JARVIS][KNOWLEDGE_WORKSPACE] INDEX_REFRESH GRAPH_REFRESH path={} nodeId={}", path, nodeId);
    }

    private Map<String, Object> eventData(String path, Map<String, Object> data) {
        Map<String, Object> values = new HashMap<>(data == null ? Map.of() : data);
        values.put("timestamp", Instant.now().toString());
        if (path != null) {
            values.put("path", path);
        }
        return values;
    }

    private KnowledgeWorkspaceNode buildNode(Path path, Map<String, KnowledgeDocument> indexedDocuments) {
        try {
            boolean root = path.equals(rootPath());
            boolean directory = Files.isDirectory(path);
            String relativePath = root ? "" : relativePath(path);
            KnowledgeDocument document = indexedDocuments.get(relativePath);
            List<KnowledgeWorkspaceNode> children = directory ? children(path, indexedDocuments) : List.of();
            return new KnowledgeWorkspaceNode(
                    root ? ROOT_NODE_ID : nodeIdFor(path),
                    document == null ? null : document.id(),
                    root ? KnowledgeNodeType.ROOT : directory ? KnowledgeNodeType.FOLDER : KnowledgeNodeType.DOCUMENT,
                    root ? "Knowledge" : path.getFileName().toString(),
                    relativePath,
                    children,
                    directory ? 0 : Files.size(path),
                    directory ? null : Files.getLastModifiedTime(path).toInstant(),
                    document != null
            );
        } catch (IOException exception) {
            throw new KnowledgeException("Failed to build knowledge workspace node " + path, exception);
        }
    }

    private List<KnowledgeWorkspaceNode> children(Path directory, Map<String, KnowledgeDocument> indexedDocuments) throws IOException {
        try (Stream<Path> paths = Files.list(directory)) {
            return paths.filter(path -> !isInternal(path))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .map(path -> buildNode(path, indexedDocuments))
                    .toList();
        }
    }

    private int count(KnowledgeWorkspaceNode node, KnowledgeNodeType type) {
        int self = node.type() == type ? 1 : 0;
        return self + node.children().stream().mapToInt(child -> count(child, type)).sum();
    }

    private void saveVersion(KnowledgeDocument document, String summary) throws IOException {
        Path source = resolveRelative(document.relativePath());
        if (!Files.isRegularFile(source)) {
            return;
        }
        Path folder = historyRoot().resolve(document.id().toString());
        ensureDirectory(folder);
        String versionId = DateTimeFormatter.ISO_INSTANT.format(Instant.now()).replace(':', '-');
        KnowledgeWorkspaceAuditContext.Audit audit = KnowledgeWorkspaceAuditContext.current();
        Path copy = folder.resolve(versionId + "__" + source.getFileName());
        Files.copy(source, copy, StandardCopyOption.REPLACE_EXISTING);
        Path metadata = folder.resolve(versionId + ".meta");
        Files.writeString(metadata, String.join(System.lineSeparator(),
                "versionId=" + versionId,
                "documentId=" + document.id(),
                "relativePath=" + document.relativePath(),
                "timestamp=" + Instant.now(),
                "author=" + AI_AUTHOR,
                "summary=" + summary,
                "conversationId=" + sanitizeAudit(audit.conversationId()),
                "requestId=" + sanitizeAudit(audit.requestId()),
                "tool=" + sanitizeAudit(audit.tool()),
                "reason=" + sanitizeAudit(audit.reason()),
                "reasoningSummary=" + sanitizeAudit(audit.reasoningSummary()),
                "previousVersionPath=" + rootPath().relativize(copy).toString().replace('\\', '/')
        ), StandardCharsets.UTF_8);
    }

    private void saveVersionsForDelete(Path target) throws IOException {
        if (Files.isRegularFile(target)) {
            knowledgeIndex.findByRelativePath(relativePath(target))
                    .ifPresent(document -> {
                        try {
                            saveVersion(document, "Delete node");
                        } catch (IOException exception) {
                            throw new KnowledgeException("Failed to save version before delete", exception);
                        }
                    });
            return;
        }
        if (Files.isDirectory(target)) {
            try (Stream<Path> paths = Files.walk(target)) {
                List<Path> files = paths.filter(Files::isRegularFile).toList();
                for (Path file : files) {
                    knowledgeIndex.findByRelativePath(relativePath(file))
                            .ifPresent(document -> {
                                try {
                                    saveVersion(document, "Delete node");
                                } catch (IOException exception) {
                                    throw new KnowledgeException("Failed to save version before delete", exception);
                                }
                            });
                }
            }
        }
    }

    private KnowledgeVersion readVersion(Path metadata) {
        try {
            Map<String, String> values = new HashMap<>();
            Files.readAllLines(metadata, StandardCharsets.UTF_8).forEach(line -> {
                int separator = line.indexOf('=');
                if (separator > 0) {
                    values.put(line.substring(0, separator), line.substring(separator + 1));
                }
            });
            return new KnowledgeVersion(
                    values.getOrDefault("versionId", metadata.getFileName().toString()),
                    values.getOrDefault("documentId", ""),
                    values.getOrDefault("relativePath", ""),
                    Instant.parse(values.getOrDefault("timestamp", Instant.EPOCH.toString())),
                    values.getOrDefault("author", AI_AUTHOR),
                    values.getOrDefault("summary", ""),
                    values.getOrDefault("conversationId", ""),
                    values.getOrDefault("requestId", ""),
                    values.getOrDefault("tool", ""),
                    values.getOrDefault("reason", ""),
                    values.getOrDefault("reasoningSummary", ""),
                    values.getOrDefault("previousVersionPath", "")
            );
        } catch (IOException exception) {
            throw new KnowledgeException("Failed to read version metadata " + metadata, exception);
        }
    }

    private void saveDraft(String tool, String path, Map<String, Object> data) {
        try {
            Path folder = draftsRoot();
            ensureDirectory(folder);
            String draftId = DateTimeFormatter.ISO_INSTANT.format(Instant.now()).replace(':', '-')
                    + "-" + UUID.randomUUID();
            Files.writeString(folder.resolve(draftId + ".draft"), String.join(System.lineSeparator(),
                    "tool=" + tool,
                    "path=" + nullToEmpty(path),
                    "timestamp=" + Instant.now(),
                    "data=" + String.valueOf(data)
            ), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new KnowledgeException("Failed to save knowledge draft", exception);
        }
    }

    private KnowledgeDocument document(UUID id) {
        return knowledgeService.getDocument(id)
                .orElseThrow(() -> new KnowledgeException("Knowledge document not found: " + id));
    }

    private Path resolveNode(String nodeId) {
        if (nodeId == null || nodeId.isBlank() || ROOT_NODE_ID.equals(nodeId)) {
            return rootPath();
        }
        if (nodeId.startsWith(DOCUMENT_PREFIX)) {
            return resolveRelative(nodeId.substring(DOCUMENT_PREFIX.length()));
        }
        if (nodeId.startsWith(FOLDER_PREFIX)) {
            return resolveRelative(nodeId.substring(FOLDER_PREFIX.length()));
        }
        return resolveRelative(nodeId);
    }

    private String parentPath(String parentId) {
        Path parent = resolveNode(parentId);
        if (!Files.exists(parent)) {
            return relativePath(parent);
        }
        if (!Files.isDirectory(parent)) {
            throw new KnowledgeException("Parent is not a folder: " + parentId);
        }
        return relativePath(parent);
    }

    private Path resolveRelative(String relativePath) {
        String cleaned = relativePath == null ? "" : relativePath.replace('\\', '/');
        while (cleaned.startsWith("/")) {
            cleaned = cleaned.substring(1);
        }
        Path resolved = rootPath().resolve(cleaned).normalize();
        if (!resolved.startsWith(rootPath())) {
            throw new KnowledgeException("Knowledge path escapes the workspace: " + relativePath);
        }
        return resolved;
    }

    private String relativePath(Path path) {
        Path root = rootPath();
        Path normalized = path.toAbsolutePath().normalize();
        if (normalized.equals(root)) {
            return "";
        }
        if (!normalized.startsWith(root)) {
            throw new KnowledgeException("Knowledge path escapes the workspace: " + path);
        }
        return root.relativize(normalized).toString().replace('\\', '/');
    }

    private String join(String parent, String child) {
        if (parent == null || parent.isBlank()) {
            return child;
        }
        return parent.replace('\\', '/') + "/" + child;
    }

    private String safeName(String name, boolean document) {
        String cleaned = name == null ? "" : name.trim().replace('\\', '/');
        if (cleaned.contains("/") || cleaned.contains("..") || cleaned.isBlank()) {
            throw new KnowledgeException("Invalid knowledge node name: " + name);
        }
        if (document && !cleaned.contains(".")) {
            cleaned = cleaned + ".md";
        }
        return cleaned;
    }

    private String nodeIdFor(Path path) {
        return Files.isDirectory(path) ? folderNodeId(relativePath(path)) : nodeId(relativePath(path));
    }

    private String nodeIdFor(Path path, boolean directory) {
        return directory ? folderNodeId(relativePath(path)) : nodeId(relativePath(path));
    }

    private String nodeId(String relativePath) {
        return DOCUMENT_PREFIX + relativePath.replace('\\', '/');
    }

    private String folderNodeId(String relativePath) {
        return FOLDER_PREFIX + relativePath.replace('\\', '/');
    }

    private Path rootPath() {
        return Path.of(knowledgeProperties.root()).toAbsolutePath().normalize();
    }

    private Path historyRoot() {
        return internalDirectory(workspaceProperties.historyDirectory());
    }

    private Path draftsRoot() {
        return internalDirectory(workspaceProperties.draftsDirectory());
    }

    private Path internalDirectory(String name) {
        Path resolved = rootPath().resolve(name).normalize();
        if (!resolved.startsWith(rootPath())) {
            throw new KnowledgeException("Internal workspace directory escapes the knowledge root: " + name);
        }
        return resolved;
    }

    private void ensureDirectory(Path directory) {
        try {
            Files.createDirectories(directory);
        } catch (IOException exception) {
            throw new KnowledgeException("Failed to create knowledge directory " + directory, exception);
        }
    }

    private boolean isInternal(Path path) {
        Path relative = rootPath().relativize(path.toAbsolutePath().normalize());
        for (Path part : relative) {
            String name = part.toString();
            if (name.equals(workspaceProperties.historyDirectory()) || name.equals(workspaceProperties.draftsDirectory())) {
                return true;
            }
        }
        return false;
    }

    private String templateFor(String fileName) {
        String normalized = fileName.toLowerCase(Locale.ROOT);
        if (normalized.contains("hardware")) {
            return "# Hardware" + System.lineSeparator() + System.lineSeparator() + "## Components" + System.lineSeparator();
        }
        if (normalized.contains("software")) {
            return "# Software" + System.lineSeparator() + System.lineSeparator() + "## Stack" + System.lineSeparator();
        }
        if (normalized.contains("project")) {
            return "# Project" + System.lineSeparator() + System.lineSeparator() + "## Goal" + System.lineSeparator();
        }
        if (normalized.contains("meeting")) {
            return "# Meeting" + System.lineSeparator() + System.lineSeparator() + "## Notes" + System.lineSeparator();
        }
        if (normalized.contains("research")) {
            return "# Research" + System.lineSeparator() + System.lineSeparator() + "## Findings" + System.lineSeparator();
        }
        if (normalized.contains("procedure")) {
            return "# Procedure" + System.lineSeparator() + System.lineSeparator() + "## Steps" + System.lineSeparator();
        }
        if (normalized.contains("configuration")) {
            return "# Configuration" + System.lineSeparator() + System.lineSeparator() + "## Values" + System.lineSeparator();
        }
        if (normalized.contains("deployment")) {
            return "# Deployment" + System.lineSeparator() + System.lineSeparator() + "## Process" + System.lineSeparator();
        }
        return "# " + fileName.replaceFirst("\\.[^.]+$", "") + System.lineSeparator();
    }

    private String withMetadata(String fileName, String content) {
        String now = Instant.now().toString();
        return "---" + System.lineSeparator()
                + "title: " + fileName.replaceFirst("\\.[^.]+$", "") + System.lineSeparator()
                + "author: AI" + System.lineSeparator()
                + "created: " + now + System.lineSeparator()
                + "updated: " + now + System.lineSeparator()
                + "source: knowledge-workspace" + System.lineSeparator()
                + "---" + System.lineSeparator()
                + System.lineSeparator()
                + content;
    }

    private String applyInstruction(String current, String instruction, String text) {
        String safeCurrent = current == null ? "" : current;
        String safeInstruction = instruction == null ? "" : instruction;
        String safeText = text == null ? "" : text;
        String lowerInstruction = safeInstruction.toLowerCase(Locale.ROOT);
        if ((lowerInstruction.contains("remove") || lowerInstruction.contains("delete") || lowerInstruction.contains("usun"))
                && !safeText.isBlank()) {
            return removeLinesContaining(safeCurrent, safeText);
        }
        StringBuilder builder = new StringBuilder(safeCurrent.stripTrailing());
        if (!builder.isEmpty()) {
            builder.append(System.lineSeparator()).append(System.lineSeparator());
        }
        builder.append("## AI Workspace Update").append(System.lineSeparator())
                .append("- Instruction: ").append(safeInstruction.isBlank() ? "Update document" : safeInstruction)
                .append(System.lineSeparator());
        if (!safeText.isBlank()) {
            builder.append("- Change: ").append(safeText).append(System.lineSeparator());
        }
        builder.append("- Updated: ").append(Instant.now()).append(System.lineSeparator());
        return builder.toString();
    }

    private String removeLinesContaining(String current, String phrase) {
        String needle = phrase.toLowerCase(Locale.ROOT);
        return current.lines()
                .filter(line -> !line.toLowerCase(Locale.ROOT).contains(needle))
                .reduce((left, right) -> left + System.lineSeparator() + right)
                .orElse("");
    }

    private KnowledgeToolResult result(
            String tool,
            boolean applied,
            boolean draft,
            String message,
            String nodeId,
            String path,
            Map<String, Object> data
    ) {
        return new KnowledgeToolResult(tool, applied, draft, message, nodeId, path, Instant.now(), data);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String sanitizeAudit(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ');
    }

    @FunctionalInterface
    private interface WorkspaceWrite {
        void apply() throws IOException;
    }
}
