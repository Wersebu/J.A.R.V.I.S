package com.jarvis.api.controller;

import com.jarvis.api.dto.KnowledgeRetrievalRequest;
import com.jarvis.api.dto.KnowledgeCreateDocumentRequest;
import com.jarvis.api.dto.KnowledgeCreateFolderRequest;
import com.jarvis.api.dto.KnowledgeDocumentContentRequest;
import com.jarvis.api.dto.KnowledgeMoveRequest;
import com.jarvis.api.dto.KnowledgeNodeRequest;
import com.jarvis.api.dto.KnowledgePathRequest;
import com.jarvis.api.dto.KnowledgeRenameRequest;
import com.jarvis.api.dto.KnowledgeToolRequest;
import com.jarvis.knowledge.KnowledgeDocument;
import com.jarvis.knowledge.KnowledgeService;
import com.jarvis.knowledge.retrieval.KnowledgeRetriever;
import com.jarvis.knowledge.retrieval.RetrievalResult;
import com.jarvis.knowledge.workspace.KnowledgeToolResult;
import com.jarvis.knowledge.workspace.KnowledgeVersion;
import com.jarvis.knowledge.workspace.KnowledgeWorkspaceService;
import com.jarvis.knowledge.workspace.KnowledgeWorkspaceTree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST API for knowledge document metadata.
 */
@RestController
@RequestMapping("/api/v1/knowledge")
public class KnowledgeController {

    private static final Logger LOGGER = LoggerFactory.getLogger(KnowledgeController.class);

    private final KnowledgeService knowledgeService;
    private final KnowledgeRetriever knowledgeRetriever;
    private final KnowledgeWorkspaceService knowledgeWorkspaceService;

    /**
     * Creates the knowledge controller.
     *
     * @param knowledgeService knowledge service
     * @param knowledgeRetriever knowledge retriever
     */
    public KnowledgeController(
            KnowledgeService knowledgeService,
            KnowledgeRetriever knowledgeRetriever,
            KnowledgeWorkspaceService knowledgeWorkspaceService
    ) {
        this.knowledgeService = knowledgeService;
        this.knowledgeRetriever = knowledgeRetriever;
        this.knowledgeWorkspaceService = knowledgeWorkspaceService;
    }

    /**
     * Lists indexed knowledge documents.
     *
     * @return document metadata list
     */
    @GetMapping
    public List<KnowledgeDocument> listDocuments() {
        LOGGER.info("[JARVIS] Knowledge list requested");
        return knowledgeService.listDocuments();
    }

    /**
     * Gets one knowledge document by identifier.
     *
     * @param id document identifier
     * @return document metadata or 404
     */
    @GetMapping("/{id}")
    public ResponseEntity<KnowledgeDocument> getDocument(@PathVariable UUID id) {
        LOGGER.info("[JARVIS] Knowledge document requested id={}", id);
        return knowledgeService.getDocument(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Rebuilds the knowledge index.
     *
     * @return rebuilt document metadata list
     */
    @PostMapping("/reindex")
    public List<KnowledgeDocument> reindex() {
        LOGGER.info("[JARVIS] Knowledge reindex requested");
        return knowledgeService.reindex();
    }

    /**
     * Retrieves knowledge documents by query.
     *
     * @param request retrieval request
     * @return retrieval result
     */
    @PostMapping("/retrieve")
    public RetrievalResult retrieve(@RequestBody KnowledgeRetrievalRequest request) {
        String query = request == null ? "" : request.query();
        LOGGER.info("[JARVIS] Knowledge retrieval requested query=\"{}\"", query);
        return knowledgeRetriever.retrieve(query);
    }

    /**
     * Returns the complete safe knowledge workspace tree.
     *
     * @return workspace tree
     */
    @GetMapping("/tree")
    public KnowledgeWorkspaceTree tree() {
        LOGGER.info("[JARVIS] Knowledge workspace tree requested");
        return knowledgeWorkspaceService.list();
    }

    /**
     * Invokes a named knowledge workspace tool.
     *
     * @param request tool request
     * @return tool result
     */
    @PostMapping("/tools")
    public Object tool(@RequestBody KnowledgeToolRequest request) {
        LOGGER.info("[JARVIS] Knowledge tool requested tool={}", request.tool());
        return invokeTool(request.tool(), request.args());
    }

    /**
     * Creates a folder through the workspace API.
     */
    @PostMapping("/folders")
    public KnowledgeToolResult createFolder(@RequestBody KnowledgeCreateFolderRequest request) {
        return knowledgeWorkspaceService.createFolder(request.parentId(), request.name());
    }

    /**
     * Creates a document through the workspace API.
     */
    @PostMapping("/documents")
    public KnowledgeToolResult createDocument(@RequestBody KnowledgeCreateDocumentRequest request) {
        return knowledgeWorkspaceService.createDocument(request.parentId(), request.name(), request.content());
    }

    /**
     * Replaces a document through the workspace API.
     */
    @PostMapping("/documents/update")
    public KnowledgeToolResult updateDocument(@RequestBody KnowledgeDocumentContentRequest request) {
        return knowledgeWorkspaceService.updateDocument(request.documentId(), request.content());
    }

    /**
     * Appends text to a document through the workspace API.
     */
    @PostMapping("/documents/append")
    public KnowledgeToolResult appendDocument(@RequestBody KnowledgeDocumentContentRequest request) {
        return knowledgeWorkspaceService.appendDocument(request.documentId(), request.content());
    }

    /**
     * Renames a workspace node.
     */
    @PostMapping("/rename")
    public KnowledgeToolResult rename(@RequestBody KnowledgeRenameRequest request) {
        return knowledgeWorkspaceService.rename(request.nodeId(), request.newName());
    }

    /**
     * Moves a workspace node.
     */
    @PostMapping("/move")
    public KnowledgeToolResult move(@RequestBody KnowledgeMoveRequest request) {
        return knowledgeWorkspaceService.move(request.nodeId(), request.newParent());
    }

    /**
     * Deletes a workspace node.
     */
    @PostMapping("/delete")
    public KnowledgeToolResult delete(@RequestBody KnowledgeNodeRequest request) {
        return knowledgeWorkspaceService.delete(request.nodeId());
    }

    /**
     * Checks whether a path exists.
     */
    @PostMapping("/exists")
    public KnowledgeToolResult exists(@RequestBody KnowledgePathRequest request) {
        return knowledgeWorkspaceService.exists(request.path());
    }

    /**
     * Rebuilds the workspace index.
     */
    @PostMapping("/index")
    public KnowledgeToolResult indexWorkspace() {
        return knowledgeWorkspaceService.index();
    }

    /**
     * Lists a document's version history.
     */
    @GetMapping("/{id}/history")
    public List<KnowledgeVersion> history(@PathVariable UUID id) {
        return knowledgeWorkspaceService.history(id);
    }

    private Object invokeTool(String tool, Map<String, Object> args) {
        return switch (tool == null ? "" : tool) {
            case "knowledge.list" -> knowledgeWorkspaceService.list();
            case "knowledge.search" -> knowledgeWorkspaceService.search(stringArg(args, "query"));
            case "knowledge.read" -> knowledgeWorkspaceService.read(uuidArg(args, "documentId"));
            case "knowledge.createFolder" -> knowledgeWorkspaceService.createFolder(
                    stringArg(args, "parentId"), stringArg(args, "name"));
            case "knowledge.createDocument" -> knowledgeWorkspaceService.createDocument(
                    stringArg(args, "parentId"), stringArg(args, "name"), stringArg(args, "content"));
            case "knowledge.updateDocument" -> knowledgeWorkspaceService.updateDocument(
                    uuidArg(args, "documentId"), stringArg(args, "newContent"));
            case "knowledge.appendDocument" -> knowledgeWorkspaceService.appendDocument(
                    uuidArg(args, "documentId"), stringArg(args, "text"));
            case "knowledge.rename" -> knowledgeWorkspaceService.rename(
                    stringArg(args, "nodeId"), stringArg(args, "newName"));
            case "knowledge.move" -> knowledgeWorkspaceService.move(
                    stringArg(args, "nodeId"), stringArg(args, "newParent"));
            case "knowledge.delete" -> knowledgeWorkspaceService.delete(stringArg(args, "nodeId"));
            case "knowledge.exists" -> knowledgeWorkspaceService.exists(stringArg(args, "path"));
            case "knowledge.index" -> knowledgeWorkspaceService.index();
            case "knowledge.history" -> knowledgeWorkspaceService.history(uuidArg(args, "documentId"));
            default -> ResponseEntity.badRequest().body(Map.of("error", "Unsupported knowledge tool: " + tool));
        };
    }

    private String stringArg(Map<String, Object> args, String name) {
        Object value = args == null ? null : args.get(name);
        return value == null ? "" : String.valueOf(value);
    }

    private UUID uuidArg(Map<String, Object> args, String name) {
        return UUID.fromString(stringArg(args, name));
    }
}
