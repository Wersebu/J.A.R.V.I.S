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

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * REST API exposing a safe cognitive graph snapshot for the Windows hologram.
 */
@RestController
@RequestMapping("/api/v1/cognitive-graph")
public class CognitiveGraphController {

    private static final String KNOWLEDGE_FOLDER_PREFIX = "knowledge-folder:";
    private static final String KNOWLEDGE_DOCUMENT_PREFIX = "knowledge-document:";
    private static final String MEMORY_ROOT_ID = "memory";
    private static final String MEMORY_CATEGORY_PREFIX = "memory-category:";
    private static final String MEMORY_RECORD_PREFIX = "memory-record:";
    private static final AtomicLong REVISION = new AtomicLong();

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
        addKnowledgeFolders(nodes, edges, knowledgeService.listDirectories());
        addKnowledgeDocuments(nodes, edges, knowledgeService.listDocuments());
        addMemory(nodes, edges, memoryService.listAll());
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
        List<MemoryRecord> memories = memoryService.listAll();
        return Map.of(
                "graphRevision", REVISION.get(),
                "watchedDirectories", directories.size(),
                "knowledgeNodes", directories.size() + documents.size(),
                "memoryNodes", memories.size(),
                "edges", Math.max(0, directories.size() + documents.size() + memories.size() - 1),
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

    private void addMemory(
            Map<String, NodeAccumulator> nodes,
            Map<String, CognitiveGraphEdgeResponse> edges,
            List<MemoryRecord> memories
    ) {
        if (memories.isEmpty()) {
            return;
        }
        put(nodes, MEMORY_ROOT_ID, "MEMORY_ROOT", "Memory", null, Map.of("semantic", true));
        Map<String, MemoryRecord> uniqueMemories = memories.stream()
                .collect(Collectors.toMap(
                        this::semanticKey,
                        Function.identity(),
                        (left, right) -> left.confidence() >= right.confidence() ? left : right,
                        LinkedHashMap::new
                ));
        for (MemoryRecord memory : uniqueMemories.values()) {
            String domain = semanticDomain(memory);
            String categoryId = MEMORY_CATEGORY_PREFIX + normalizeId(domain);
            put(nodes, categoryId, "MEMORY_CATEGORY", domain, MEMORY_ROOT_ID, Map.of(
                    "category", domain,
                    "semantic", true
            ));
            connect(edges, MEMORY_ROOT_ID, categoryId, "PARENT_CHILD");
            String memoryId = MEMORY_RECORD_PREFIX + memory.id();
            String title = semanticTitle(memory);
            put(nodes, memoryId, "MEMORY_RECORD", title, categoryId, Map.of(
                    "category", domain,
                    "connected", semanticConnections(memory),
                    "confidence", memory.confidence(),
                    "visualSize", visualSize(memory),
                    "priority", memory.priority() == null ? "NORMAL" : memory.priority().name(),
                    "created", memory.createdAt() == null ? "" : memory.createdAt().toString(),
                    "updated", memory.updatedAt() == null ? "" : memory.updatedAt().toString()
            ));
            connect(edges, categoryId, memoryId, "PARENT_CHILD");
        }
    }

    private String semanticDomain(MemoryRecord memory) {
        String text = semanticText(memory);
        String lower = text.toLowerCase(Locale.ROOT);
        if (containsAny(lower, "rtx", "gpu", "graphics", "graphics card", "nvidia", "aorus", "cpu", "hardware", "pc")) {
            return "Hardware";
        }
        if (containsAny(lower, "audi", "bmw", "mercedes", "vehicle", "car", "engine", "mpi", "tdi")) {
            return "Vehicles";
        }
        if (containsAny(lower, "spring", "maven", "java", "jdk", "intellij", "unreal", "programming", "code")) {
            return "Programming";
        }
        if (containsAny(lower, "project", "jarvis", "nova", "server", "core", "windows")) {
            return "Projects";
        }
        return switch (memory.category() == null ? com.jarvis.common.memory.MemoryCategory.SEMANTIC : memory.category()) {
            case DEVICE -> "Hardware";
            case VEHICLE -> "Vehicles";
            case PROJECT -> "Projects";
            case PROGRAMMING -> "Programming";
            case WORK -> "Work";
            case LOCATION -> "Places";
            case PREFERENCE, PERSON, RELATIONSHIP -> "Personal";
            case TEMPORARY -> "Temporary";
            case SEMANTIC -> "Memory";
        };
    }

    private String semanticTitle(MemoryRecord memory) {
        String text = semanticText(memory)
                .replaceAll("(?i)^user\\s+(remembers|owns|has|uses|likes|prefers|works\\s+on|develops)\\s+", "")
                .replaceAll("(?i)^the\\s+user\\s+(remembers|owns|has|uses|likes|prefers|works\\s+on|develops)\\s+", "")
                .replaceAll("(?i)^user\\s+is\\s+", "")
                .replaceAll("\\.$", "")
                .trim();
        if (text.isBlank() || text.equalsIgnoreCase("user remembers")) {
            return "Memory";
        }
        return text.length() <= 80 ? text : text.substring(0, 80);
    }

    private List<String> semanticConnections(MemoryRecord memory) {
        String lower = semanticText(memory).toLowerCase(Locale.ROOT);
        return List.of("GPU", "NVIDIA", "PC", "Gaming", "Java", "Spring", "Audi", "Project").stream()
                .filter(token -> lower.contains(token.toLowerCase(Locale.ROOT)))
                .toList();
    }

    private String semanticKey(MemoryRecord memory) {
        return semanticDomain(memory).toLowerCase(Locale.ROOT) + ":" + semanticTitle(memory)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "");
    }

    private int visualSize(MemoryRecord memory) {
        int confidence = (int) Math.round(Math.max(0.0d, Math.min(1.0d, memory.confidence())) * 8.0d);
        int connected = semanticConnections(memory).size();
        return Math.max(1, 1 + confidence + connected);
    }

    private boolean containsAny(String value, String... tokens) {
        for (String token : tokens) {
            if (value.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private String semanticText(MemoryRecord memory) {
        return Optional.ofNullable(memory.title())
                .filter(title -> !title.isBlank() && !title.equalsIgnoreCase("user remembers"))
                .orElseGet(() -> memory.content() == null ? "" : memory.content())
                .replaceAll("\\s+", " ")
                .trim();
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

    private String parentPath(String relativePath) {
        String normalized = normalize(relativePath);
        int slash = normalized.lastIndexOf('/');
        return slash > 0 ? normalized.substring(0, slash) : "";
    }

    private String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String normalizeId(String value) {
        return value == null ? "memory" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
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
