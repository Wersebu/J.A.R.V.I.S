package com.jarvis.api.controller;

import com.jarvis.api.dto.MemoryReindexResponse;
import com.jarvis.api.dto.MemorySearchCandidateResponse;
import com.jarvis.api.dto.MemorySearchDebugResponse;
import com.jarvis.api.dto.MemorySearchRequest;
import com.jarvis.common.memory.MemoryRecord;
import com.jarvis.common.memory.MemorySearchResult;
import com.jarvis.knowledge.workspace.KnowledgeToolResult;
import com.jarvis.knowledge.workspace.KnowledgeWorkspaceService;
import com.jarvis.memory.cognitive.MemoryProperties;
import com.jarvis.memory.cognitive.CognitiveMemoryService;
import com.jarvis.memory.cognitive.SemanticMemoryRecord;
import com.jarvis.memory.cognitive.SemanticMemoryStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * REST controller for cognitive memory operations.
 */
@RestController
@RequestMapping("/api/v1/memory")
public class MemoryController {

    private final CognitiveMemoryService memoryService;
    private final SemanticMemoryStore semanticMemoryStore;
    private final KnowledgeWorkspaceService knowledgeWorkspaceService;
    private final MemoryProperties memoryProperties;

    /**
     * Creates the memory controller.
     *
     * @param memoryService cognitive memory service
     * @param semanticMemoryStore legacy semantic memory store
     * @param knowledgeWorkspaceService knowledge workspace service
     * @param memoryProperties memory configuration
     */
    public MemoryController(
            CognitiveMemoryService memoryService,
            SemanticMemoryStore semanticMemoryStore,
            KnowledgeWorkspaceService knowledgeWorkspaceService,
            MemoryProperties memoryProperties
    ) {
        this.memoryService = memoryService;
        this.semanticMemoryStore = semanticMemoryStore;
        this.knowledgeWorkspaceService = knowledgeWorkspaceService;
        this.memoryProperties = memoryProperties;
    }

    /**
     * Lists all long-term memories.
     *
     * @return memories
     */
    @GetMapping
    public List<MemoryRecord> list() {
        if (!memoryProperties.legacy().retrievalEnabled()) {
            return List.of();
        }
        return memoryService.listAll();
    }

    /**
     * Searches memories.
     *
     * @param query query text
     * @return search result
     */
    @GetMapping("/search")
    public MemorySearchResult search(@RequestParam("query") String query) {
        if (!memoryProperties.legacy().retrievalEnabled()) {
            return new MemorySearchResult(query, 0L, List.of());
        }
        return memoryService.search(query);
    }

    /**
     * Searches memories with a JSON request body for diagnostics.
     *
     * @param request search request
     * @return debug search response
     */
    @PostMapping("/search")
    public MemorySearchDebugResponse searchPost(@RequestBody MemorySearchRequest request) {
        if (!memoryProperties.legacy().retrievalEnabled()) {
            return new MemorySearchDebugResponse("", 0L, List.of(), List.of(), null);
        }
        MemorySearchResult result = memoryService.search(request.query());
        return new MemorySearchDebugResponse(
                result.embeddingModel(),
                result.embeddingTimeMs(),
                result.matches().stream()
                        .map(match -> new MemorySearchCandidateResponse(
                                match.memory().id().toString(),
                                match.memory().content(),
                                match.similarity(),
                                match.score()
                        ))
                        .toList(),
                result.normalizedQuery(),
                result.memories().isEmpty() ? null : result.memories().getFirst().content()
        );
    }

    /**
     * Rebuilds memory indexes.
     *
     * @return reindex response
     */
    @PostMapping("/reindex")
    public MemoryReindexResponse reindex() {
        if (!memoryProperties.legacy().retrievalEnabled()) {
            return new MemoryReindexResponse("legacy semantic memory disabled", 0);
        }
        return new MemoryReindexResponse("indexed", memoryService.reindex());
    }

    /**
     * Describes the active memory architecture.
     *
     * @return architecture status
     */
    @GetMapping("/architecture")
    public MemoryArchitectureResponse architecture() {
        int legacyRecords = semanticMemoryStore.listAll().size();
        return new MemoryArchitectureResponse(
                "KNOWLEDGE_WORKSPACE_FILES",
                "SQLITE_WORKING_CONVERSATION_CONTEXT",
                false,
                memoryProperties.semanticSql().enabled(),
                memoryProperties.backgroundAgent().enabled(),
                memoryProperties.legacy().retrievalEnabled(),
                memoryProperties.legacy().writesEnabled(),
                memoryProperties.legacy().keepTables(),
                legacyRecords,
                legacyRecords > 0 ? "LEGACY_DATA_PRESENT" : "NO_LEGACY_DATA"
        );
    }

    /**
     * Returns legacy semantic memory records for migration diagnostics.
     *
     * @return legacy records
     */
    @GetMapping("/legacy/export")
    public List<SemanticMemoryRecord> exportLegacy() {
        return semanticMemoryStore.listAll();
    }

    /**
     * Returns legacy semantic memory status.
     *
     * @return legacy status
     */
    @GetMapping("/legacy/status")
    public LegacyMemoryStatusResponse legacyStatus() {
        int records = semanticMemoryStore.listAll().size();
        return new LegacyMemoryStatusResponse(
                records,
                memoryProperties.legacy().retrievalEnabled(),
                memoryProperties.legacy().writesEnabled(),
                memoryProperties.legacy().keepTables(),
                records > 0 ? "pending migration" : "empty"
        );
    }

    /**
     * Creates Knowledge Workspace drafts from legacy semantic memories.
     *
     * @return created migration drafts
     */
    @PostMapping("/legacy/create-migration-drafts")
    public LegacyMigrationDraftResponse createMigrationDrafts() {
        Map<String, List<SemanticMemoryRecord>> grouped = semanticMemoryStore.listAll().stream()
                .collect(Collectors.groupingBy(record -> record.category().name(), Collectors.toList()));
        List<KnowledgeToolResult> drafts = grouped.entrySet().stream()
                .map(entry -> knowledgeWorkspaceService.createDocument(
                        "knowledge-folder:LegacyMemoryMigration",
                        safeDraftName(entry.getKey()),
                        migrationContent(entry.getKey(), entry.getValue())
                ))
                .toList();
        return new LegacyMigrationDraftResponse(grouped.values().stream().mapToInt(List::size).sum(), drafts.size(), drafts);
    }

    /**
     * Marks legacy semantic memory as archived for the active architecture.
     *
     * @return archive status
     */
    @PostMapping("/legacy/archive")
    public LegacyMemoryStatusResponse archiveLegacy() {
        int records = semanticMemoryStore.listAll().size();
        return new LegacyMemoryStatusResponse(records, false, false, memoryProperties.legacy().keepTables(), "archived by configuration");
    }

    /**
     * Deletes legacy semantic memory after explicit confirmation.
     *
     * @param confirmation exact confirmation text
     * @return deletion status
     */
    @DeleteMapping("/legacy")
    public ResponseEntity<LegacyDeleteResponse> deleteLegacy(@RequestParam("confirm") String confirmation) {
        if (!"DELETE_LEGACY_MEMORY".equals(confirmation)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new LegacyDeleteResponse(false, 0, "Explicit confirmation required: DELETE_LEGACY_MEMORY"));
        }
        List<SemanticMemoryRecord> records = semanticMemoryStore.listAll();
        int deleted = 0;
        for (SemanticMemoryRecord record : records) {
            if (semanticMemoryStore.delete(record.id())) {
                deleted++;
            }
        }
        return ResponseEntity.ok(new LegacyDeleteResponse(true, deleted, "Legacy semantic memory deleted"));
    }

    /**
     * Deletes a memory.
     *
     * @param id memory identifier
     * @return empty response
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        if (!memoryProperties.legacy().writesEnabled()) {
            return ResponseEntity.notFound().build();
        }
        if (memoryService.delete(id)) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }

    private String safeDraftName(String category) {
        String normalized = category == null || category.isBlank() ? "Semantic" : category.toLowerCase(Locale.ROOT);
        normalized = normalized.replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return "Legacy-" + (normalized.isBlank() ? "semantic" : normalized) + ".md";
    }

    private String migrationContent(String category, List<SemanticMemoryRecord> records) {
        StringBuilder builder = new StringBuilder();
        builder.append("# Legacy Memory Migration - ").append(category).append("\n\n");
        builder.append("These facts were exported from legacy SQLite semantic memory.\n");
        builder.append("Review before approving this Knowledge Workspace draft.\n\n");
        for (SemanticMemoryRecord record : records) {
            builder.append("- ")
                    .append(record.subject()).append(' ')
                    .append(record.predicate()).append(' ')
                    .append(record.value())
                    .append(" (confidence: ")
                    .append("%.2f".formatted(record.confidence()))
                    .append(")\n");
        }
        return builder.toString();
    }

    /**
     * Memory architecture response.
     */
    public record MemoryArchitectureResponse(
            String longTermMemoryBackend,
            String conversationContextBackend,
            boolean durableSemanticSqlActive,
            boolean semanticSqlEnabled,
            boolean backgroundAgentEnabled,
            boolean legacyRetrievalEnabled,
            boolean legacyWritesEnabled,
            boolean keepLegacyTables,
            int legacySemanticRecords,
            String migrationStatus
    ) {
    }

    /**
     * Legacy memory status response.
     */
    public record LegacyMemoryStatusResponse(
            int records,
            boolean retrievalEnabled,
            boolean writesEnabled,
            boolean keepTables,
            String status
    ) {
    }

    /**
     * Legacy migration draft response.
     */
    public record LegacyMigrationDraftResponse(
            int sourceRecords,
            int draftsCreated,
            List<KnowledgeToolResult> drafts
    ) {
    }

    /**
     * Legacy delete response.
     */
    public record LegacyDeleteResponse(boolean deleted, int recordsDeleted, String message) {
    }
}
