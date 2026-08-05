package com.jarvis.api.controller;

import com.jarvis.api.dto.CognitiveGraphEdgeResponse;
import com.jarvis.api.dto.CognitiveGraphNodeResponse;
import com.jarvis.api.dto.CognitiveGraphResponse;
import com.jarvis.common.memory.MemoryRecord;
import com.jarvis.knowledge.KnowledgeDocument;
import com.jarvis.knowledge.KnowledgeService;
import com.jarvis.memory.cognitive.CognitiveMemoryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST API exposing a safe cognitive graph snapshot for the Windows hologram.
 */
@RestController
@RequestMapping("/api/v1/cognitive-graph")
public class CognitiveGraphController {

    private static final String KNOWLEDGE_PREFIX = "knowledge:";
    private static final String MEMORY_ROOT_ID = "memory";

    private final KnowledgeService knowledgeService;
    private final CognitiveMemoryService memoryService;

    /**
     * Creates the cognitive graph controller.
     *
     * @param knowledgeService knowledge service
     * @param memoryService memory service
     */
    public CognitiveGraphController(KnowledgeService knowledgeService, CognitiveMemoryService memoryService) {
        this.knowledgeService = knowledgeService;
        this.memoryService = memoryService;
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
        addKnowledge(nodes, edges, knowledgeService.listDocuments());
        addMemory(nodes, edges, memoryService.listAll());
        return new CognitiveGraphResponse(
                nodes.values().stream().map(NodeAccumulator::response).toList(),
                List.copyOf(edges.values())
        );
    }

    private void addKnowledge(
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
            String documentId = KNOWLEDGE_PREFIX + relativePath;
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
            String id = KNOWLEDGE_PREFIX + path;
            put(nodes, id, "KNOWLEDGE_FOLDER", part, parentId, Map.of("path", path.toString()));
            if (parentId != null) {
                connect(edges, parentId, id, "PARENT_CHILD");
            }
            parentId = id;
        }
        return parentId;
    }

    private void addMemory(
            Map<String, NodeAccumulator> nodes,
            Map<String, CognitiveGraphEdgeResponse> edges,
            List<MemoryRecord> memories
    ) {
        if (memories.isEmpty()) {
            return;
        }
        put(nodes, MEMORY_ROOT_ID, "MEMORY_CLUSTER", "Memory", null, Map.of());
        for (MemoryRecord memory : memories) {
            String category = memory.category() == null ? "SEMANTIC" : memory.category().name();
            String categoryId = "memory:" + category;
            put(nodes, categoryId, "MEMORY_CATEGORY", category, MEMORY_ROOT_ID, Map.of("category", category));
            connect(edges, MEMORY_ROOT_ID, categoryId, "PARENT_CHILD");
            String memoryId = "memory:" + category + ":" + memory.id();
            put(nodes, memoryId, "MEMORY", safeTitle(memory), categoryId, Map.of(
                    "category", category,
                    "confidence", memory.confidence(),
                    "priority", memory.priority() == null ? "NORMAL" : memory.priority().name(),
                    "created", memory.createdAt() == null ? "" : memory.createdAt().toString(),
                    "updated", memory.updatedAt() == null ? "" : memory.updatedAt().toString()
            ));
            connect(edges, categoryId, memoryId, "PARENT_CHILD");
        }
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

    private String safeTitle(MemoryRecord memory) {
        if (memory.title() != null && !memory.title().isBlank()) {
            return memory.title();
        }
        if (memory.content() == null || memory.content().isBlank()) {
            return "Memory";
        }
        String content = memory.content().replaceAll("\\s+", " ").trim();
        return content.length() <= 80 ? content : content.substring(0, 80);
    }

    private String fileName(String relativePath) {
        String normalized = normalize(relativePath);
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
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
