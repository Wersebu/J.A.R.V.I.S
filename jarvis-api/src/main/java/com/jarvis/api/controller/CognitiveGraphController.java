package com.jarvis.api.controller;

import com.jarvis.api.dto.CognitiveGraphEdgeResponse;
import com.jarvis.api.dto.CognitiveGraphNodeResponse;
import com.jarvis.api.dto.CognitiveGraphResponse;
import com.jarvis.knowledge.KnowledgeDocument;
import com.jarvis.knowledge.KnowledgeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * REST API exposing a safe cognitive graph snapshot for the Windows hologram.
 */
@RestController
@RequestMapping("/api/v1/cognitive-graph")
public class CognitiveGraphController {

    private static final String KNOWLEDGE_FOLDER_PREFIX = "knowledge-folder:";
    private static final String KNOWLEDGE_DOCUMENT_PREFIX = "knowledge-document:";
    private static final AtomicLong REVISION = new AtomicLong();

    private final KnowledgeService knowledgeService;

    /**
     * Creates the cognitive graph controller.
     *
     * @param knowledgeService knowledge service
     */
    public CognitiveGraphController(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    /**
     * Returns a complete graph snapshot.
     *
     * @return cognitive graph
     */
    @GetMapping
    public CognitiveGraphResponse graph() {
        Map<String, NodeAccumulator> nodes = new LinkedHashMap<>();
        Map<String, CognitiveGraphEdgeResponse> edges = new LinkedHashMap<>();
        addKnowledgeFolders(nodes, edges, knowledgeService.listDirectories());
        addKnowledgeDocuments(nodes, edges, knowledgeService.listDocuments());
        return new CognitiveGraphResponse(
                REVISION.incrementAndGet(),
                Instant.now().toString(),
                nodes.values().stream().map(NodeAccumulator::response).toList(),
                List.copyOf(edges.values())
        );
    }

    /**
     * Returns lightweight graph diagnostics.
     *
     * @return diagnostics
     */
    @GetMapping("/debug")
    public Map<String, Object> debug() {
        List<String> directories = knowledgeService.listDirectories();
        List<KnowledgeDocument> documents = knowledgeService.listDocuments();
        return Map.of(
                "graphRevision", REVISION.get(),
                "watchedDirectories", directories.size(),
                "knowledgeNodes", directories.size() + documents.size(),
                "memoryNodes", 0,
                "memoryBackend", "KNOWLEDGE_FILES",
                "edges", Math.max(0, directories.size() + documents.size() - 1),
                "activeRequests", 0,
                "unmatchedActivityEvents", List.of(),
                "lastFilesystemEvents", List.of()
        );
    }

    private void addKnowledgeFolders(
            Map<String, NodeAccumulator> nodes,
            Map<String, CognitiveGraphEdgeResponse> edges,
            List<String> directories
    ) {
        for (String directory : directories) {
            String normalized = normalize(directory);
            if (!normalized.isBlank()) {
                addFolder(nodes, edges, normalized);
            }
        }
    }

    private void addKnowledgeDocuments(
            Map<String, NodeAccumulator> nodes,
            Map<String, CognitiveGraphEdgeResponse> edges,
            List<KnowledgeDocument> documents
    ) {
        for (KnowledgeDocument document : documents) {
            String relativePath = normalize(document.relativePath());
            if (relativePath.isBlank()) {
                continue;
            }
            String parentId = addFolderPath(nodes, edges, relativePath);
            String documentId = KNOWLEDGE_DOCUMENT_PREFIX + relativePath;
            put(nodes, documentId, "KNOWLEDGE_DOCUMENT", safe(document.title(), fileName(relativePath)), parentId, Map.of(
                    "path", relativePath,
                    "indexed", document.status() == null ? "INDEXED" : document.status().name(),
                    "embedding", true,
                    "size", document.fileSize(),
                    "updated", document.modified() == null ? "" : document.modified().toString(),
                    "documentId", document.id().toString()
            ));
            connect(edges, parentId, documentId, "PARENT_CHILD");
        }
    }

    private String addFolderPath(
            Map<String, NodeAccumulator> nodes,
            Map<String, CognitiveGraphEdgeResponse> edges,
            String relativePath
    ) {
        String normalized = normalize(relativePath);
        String[] parts = normalized.split("/");
        String parentId = null;
        StringBuilder path = new StringBuilder();
        for (int index = 0; index < Math.max(0, parts.length - 1); index++) {
            String part = parts[index];
            if (part.isBlank()) {
                continue;
            }
            if (!path.isEmpty()) {
                path.append('/');
            }
            path.append(part);
            String id = KNOWLEDGE_FOLDER_PREFIX + path;
            put(nodes, id, "KNOWLEDGE_FOLDER", part, parentId, Map.of("path", path.toString()));
            if (parentId != null) {
                connect(edges, parentId, id, "PARENT_CHILD");
            }
            parentId = id;
        }
        return parentId;
    }

    private String addFolder(
            Map<String, NodeAccumulator> nodes,
            Map<String, CognitiveGraphEdgeResponse> edges,
            String directory
    ) {
        String normalized = normalize(directory);
        String parentPath = parentPath(normalized);
        String parentId = parentPath.isBlank() ? null : addFolder(nodes, edges, parentPath);
        String id = KNOWLEDGE_FOLDER_PREFIX + normalized;
        put(nodes, id, "KNOWLEDGE_FOLDER", fileName(normalized), parentId, Map.of("path", normalized));
        connect(edges, parentId, id, "PARENT_CHILD");
        return id;
    }

    private void put(
            Map<String, NodeAccumulator> nodes,
            String id,
            String type,
            String name,
            String parentId,
            Map<String, Object> metadata
    ) {
        nodes.compute(id, (ignored, existing) -> {
            if (existing == null) {
                return new NodeAccumulator(id, type, name, parentId, metadata);
            }
            existing.merge(metadata);
            return existing;
        });
        if (parentId != null && nodes.containsKey(parentId)) {
            nodes.get(parentId).incrementChildCount();
        }
    }

    private void connect(Map<String, CognitiveGraphEdgeResponse> edges, String source, String target, String type) {
        if (source == null || target == null || source.isBlank() || target.isBlank() || source.equals(target)) {
            return;
        }
        edges.putIfAbsent(source + "->" + target, new CognitiveGraphEdgeResponse(source, target, type));
    }

    private String normalize(String path) {
        return path == null ? "" : path.replace('\\', '/').replaceAll("^/+", "").trim();
    }

    private String fileName(String relativePath) {
        String normalized = normalize(relativePath);
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }

    private String parentPath(String relativePath) {
        String normalized = normalize(relativePath);
        int slash = normalized.lastIndexOf('/');
        return slash > 0 ? normalized.substring(0, slash) : "";
    }

    private String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static final class NodeAccumulator {
        private final String id;
        private final String type;
        private final String name;
        private final String parentId;
        private final Map<String, Object> metadata;
        private int childCount;

        private NodeAccumulator(String id, String type, String name, String parentId, Map<String, Object> metadata) {
            this.id = id;
            this.type = type;
            this.name = name;
            this.parentId = parentId;
            this.metadata = new HashMap<>(metadata == null ? Map.of() : metadata);
        }

        private void merge(Map<String, Object> values) {
            if (values != null) {
                metadata.putAll(values);
            }
        }

        private void incrementChildCount() {
            childCount++;
        }

        private CognitiveGraphNodeResponse response() {
            return new CognitiveGraphNodeResponse(id, type, name, parentId, childCount, Map.copyOf(metadata));
        }
    }
}
