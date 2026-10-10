package com.jarvis.api.controller;

import com.jarvis.knowledge.KnowledgeException;
import com.jarvis.knowledge.retrieval.KnowledgeRetriever;
import com.jarvis.knowledge.retrieval.RetrievalDocument;
import com.jarvis.knowledge.retrieval.RetrievalResult;
import com.jarvis.knowledge.vault.KnowledgeVaultService;
import com.jarvis.knowledge.vault.VaultPathPolicy;
import com.jarvis.knowledge.vault.VaultSourceTracker;
import com.jarvis.knowledge.vault.edit.VaultDocumentService;
import com.jarvis.knowledge.vault.edit.VaultDocumentView;
import com.jarvis.knowledge.vault.edit.VaultEditException;
import com.jarvis.knowledge.vault.edit.VaultTreeNode;
import com.jarvis.knowledge.vault.edit.VaultWriteResult;
import com.jarvis.knowledge.vault.index.VaultIndexStatus;
import com.jarvis.knowledge.vault.read.PagedDocument;
import com.jarvis.knowledge.vault.read.WorkflowCandidate;
import com.jarvis.knowledge.vault.search.VaultSearchHit;
import com.jarvis.knowledge.vault.search.VaultSearchQuery;
import com.jarvis.knowledge.vault.search.VaultSearchResult;
import com.jarvis.knowledge.vault.search.VaultSearchService;
import com.jarvis.knowledge.workspace.KnowledgeVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST API of the Obsidian-compatible knowledge vault used by the Windows "Pamięć i wiedza" window.
 *
 * <p>Writes are user-authored edits guarded by version tokens: a stale token returns
 * {@code 409 CONFLICT} with the current content instead of overwriting a parallel Obsidian edit.
 */
@RestController
@RequestMapping("/api/v1/vault")
public class VaultController {

    private static final Logger LOGGER = LoggerFactory.getLogger(VaultController.class);

    private final KnowledgeVaultService vaultService;
    private final VaultDocumentService documentService;
    private final KnowledgeRetriever legacyRetriever;

    /**
     * Creates the controller.
     *
     * @param vaultService vault service
     * @param documentService user edit service
     * @param legacyRetriever previous retriever, used for search while knowledge.vault.mode=LEGACY
     */
    public VaultController(KnowledgeVaultService vaultService, VaultDocumentService documentService, KnowledgeRetriever legacyRetriever) {
        this.vaultService = vaultService;
        this.documentService = documentService;
        this.legacyRetriever = legacyRetriever;
    }

    /**
     * Returns mode, index and embedding provider status.
     *
     * @return status
     */
    @GetMapping("/status")
    public VaultIndexStatus status() {
        return vaultService.status();
    }

    /**
     * Returns the folder / note / attachment tree.
     *
     * @return tree
     */
    @GetMapping("/tree")
    public VaultTreeNode tree() {
        return documentService.tree();
    }

    /**
     * Reads a document with metadata and its version token.
     *
     * @param path relative path
     * @return document
     */
    @GetMapping("/document")
    public VaultDocumentView document(@RequestParam("path") String path) {
        return documentService.read(path);
    }

    /**
     * Saves a document if it still has {@code expectedVersion}.
     *
     * @param request path, content, expectedVersion
     * @return new version
     */
    @PutMapping("/document")
    public VaultWriteResult save(@RequestBody Map<String, String> request) {
        return documentService.save(request.get("path"), request.get("content"), request.get("expectedVersion"));
    }

    /**
     * Creates a document.
     *
     * @param request path, content
     * @return result
     */
    @PostMapping("/document")
    public ResponseEntity<VaultWriteResult> create(@RequestBody Map<String, String> request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(documentService.create(request.get("path"), request.get("content")));
    }

    /**
     * Creates a folder.
     *
     * @param request path
     * @return result
     */
    @PostMapping("/folder")
    public ResponseEntity<VaultWriteResult> createFolder(@RequestBody Map<String, String> request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(documentService.createFolder(request.get("path")));
    }

    /**
     * Renames or moves a node (never replaces an existing target).
     *
     * @param request from, to, optional expectedVersion
     * @return result
     */
    @PostMapping("/move")
    public VaultWriteResult move(@RequestBody Map<String, String> request) {
        return documentService.move(request.get("from"), request.get("to"), request.get("expectedVersion"));
    }

    /**
     * Searches fragments ({@code mode=text|hybrid}) with filters.
     *
     * @return result
     */
    @GetMapping("/search")
    public VaultSearchResult search(
            @RequestParam("q") String query,
            @RequestParam(value = "mode", defaultValue = "hybrid") String mode,
            @RequestParam(value = "type", required = false) List<String> types,
            @RequestParam(value = "project", required = false) String project,
            @RequestParam(value = "tags", required = false) List<String> tags,
            @RequestParam(value = "status", required = false) List<String> statuses,
            @RequestParam(value = "folder", required = false) String folder,
            @RequestParam(value = "includeArchived", defaultValue = "false") boolean includeArchived,
            @RequestParam(value = "workflowsOnly", defaultValue = "false") boolean workflowsOnly,
            @RequestParam(value = "limit", defaultValue = "0") int limit
    ) {
        if (!vaultService.active()) {
            return legacySearch(query);
        }
        VaultSearchQuery.Mode searchMode = "text".equalsIgnoreCase(mode) ? VaultSearchQuery.Mode.TEXT : VaultSearchQuery.Mode.HYBRID;
        return vaultService.search(new VaultSearchQuery(query, searchMode, types, project, tags, statuses, folder, includeArchived,
                workflowsOnly, limit > 0 ? limit : 20, limit > 0 ? 0 : 20_000), null, null);
    }

    /**
     * Reads a document as the model would receive it (complete or one explicit part).
     *
     * @param path path
     * @param part part
     * @return part
     */
    @GetMapping("/read")
    public PagedDocument read(@RequestParam("path") String path, @RequestParam(value = "part", defaultValue = "1") int part) {
        return vaultService.read(path, part, null, null);
    }

    /**
     * Lists workflow candidates.
     *
     * @param query query ({@code ""} lists all)
     * @param includeArchived include archived workflows
     * @return candidates
     */
    @GetMapping("/workflows")
    public List<WorkflowCandidate> workflows(@RequestParam(value = "q", defaultValue = "") String query,
                                             @RequestParam(value = "includeArchived", defaultValue = "false") boolean includeArchived) {
        return vaultService.findWorkflows(query, includeArchived);
    }

    /**
     * Lists versions of a document.
     *
     * @param path path
     * @return versions
     */
    @GetMapping("/history")
    public List<KnowledgeVersion> history(@RequestParam("path") String path) {
        return documentService.history(path);
    }

    /**
     * Returns the content of a stored version.
     *
     * @param path path
     * @param versionId version
     * @return content
     */
    @GetMapping("/history/version")
    public Map<String, String> version(@RequestParam("path") String path, @RequestParam("versionId") String versionId) {
        return Map.of("path", path, "versionId", versionId, "content", documentService.versionContent(path, versionId));
    }

    /**
     * Restores a version.
     *
     * @param request path, versionId, expectedVersion
     * @return result
     */
    @PostMapping("/history/restore")
    public VaultWriteResult restore(@RequestBody Map<String, String> request) {
        return documentService.restore(request.get("path"), request.get("versionId"), request.get("expectedVersion"));
    }

    /**
     * Reconciles the index with the files (changed files only).
     *
     * @return changes
     */
    @PostMapping("/reindex")
    public Map<String, Object> reindex() {
        LOGGER.info("[VAULT] Manual reindex requested");
        return Map.of("changes", vaultService.reindex(), "status", vaultService.status());
    }

    /**
     * Drops and rebuilds the chunk index from the Markdown files.
     *
     * @return changes
     */
    @PostMapping("/rebuild")
    public Map<String, Object> rebuild() {
        LOGGER.info("[VAULT] Index rebuild requested");
        return Map.of("changes", vaultService.rebuild(), "status", vaultService.status());
    }

    /**
     * Returns vault sources given to the model in a conversation.
     *
     * @param conversationId conversation
     * @param limit maximum entries
     * @return sources, newest first
     */
    @GetMapping("/sources")
    public List<VaultSourceTracker.SourceUse> sources(@RequestParam("conversationId") String conversationId,
                                                      @RequestParam(value = "limit", defaultValue = "30") int limit) {
        return vaultService.sources().forConversation(conversationId, limit);
    }

    /**
     * Maps edit rejections to HTTP statuses.
     *
     * @param exception rejection
     * @return error body
     */
    @ExceptionHandler(VaultEditException.class)
    public ResponseEntity<Map<String, Object>> handleEdit(VaultEditException exception) {
        HttpStatus status = switch (exception.reason()) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT, ALREADY_EXISTS -> HttpStatus.CONFLICT;
            case EXCLUDED -> HttpStatus.FORBIDDEN;
            case INVALID -> HttpStatus.BAD_REQUEST;
        };
        Map<String, Object> body = new LinkedHashMap<>(exception.details());
        body.put("error", exception.reason().name());
        body.put("message", exception.getMessage());
        return ResponseEntity.status(status).body(body);
    }

    /**
     * Maps rejected paths.
     *
     * @param exception rejection
     * @return error body
     */
    @ExceptionHandler(VaultPathPolicy.VaultPathException.class)
    public ResponseEntity<Map<String, Object>> handlePath(VaultPathPolicy.VaultPathException exception) {
        return ResponseEntity.badRequest().body(Map.of("error", "INVALID", "message", exception.getMessage()));
    }

    /**
     * Maps other knowledge errors (e.g. document not found or excluded on read).
     *
     * @param exception error
     * @return error body
     */
    @ExceptionHandler(KnowledgeException.class)
    public ResponseEntity<Map<String, Object>> handleKnowledge(KnowledgeException exception) {
        String message = exception.getMessage() == null ? "Knowledge error" : exception.getMessage();
        HttpStatus status = message.startsWith("Document not found") ? HttpStatus.NOT_FOUND
                : message.contains("excluded") || message.contains("system prompt") || message.contains("credentials")
                ? HttpStatus.FORBIDDEN : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(Map.of("error", status.name(), "message", message));
    }

    /**
     * Vault index disabled (LEGACY mode).
     *
     * @param exception error
     * @return error body
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleDisabled(IllegalStateException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "VAULT_DISABLED", "message", String.valueOf(exception.getMessage())));
    }

    private VaultSearchResult legacySearch(String query) {
        long started = System.nanoTime();
        RetrievalResult legacy = legacyRetriever.retrieve(query);
        List<VaultSearchHit> hits = new ArrayList<>();
        for (RetrievalDocument document : legacy.documents()) {
            hits.add(new VaultSearchHit(hits.size() + 1, "", document.documentId().toString(), document.relativePath(), document.title(),
                    List.of(), 0, 0, document.preview(), 0, "", "", "", List.of(), "", "", "", false, document.score(), null,
                    document.score(), List.of("legacy")));
        }
        return new VaultSearchResult(legacy.query(), "LEGACY", "LEGACY", hits, List.of(), hits.size(), false,
                "knowledge.vault.mode=LEGACY: document-level search over previews", 0, (System.nanoTime() - started) / 1_000_000L,
                hits.isEmpty(), List.of(VaultSearchService.DATA_NOTE, "Legacy mode: results are documents with a "
                + "preview of their beginning, not full-content fragments."));
    }
}
