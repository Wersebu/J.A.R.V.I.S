package com.jarvis.tools.knowledge;

import com.jarvis.common.event.CognitiveEventBus;
import com.jarvis.common.event.CognitiveEventType;
import com.jarvis.knowledge.retrieval.RetrievalDocument;
import com.jarvis.knowledge.retrieval.RetrievalResult;
import com.jarvis.knowledge.workspace.KnowledgeNodeType;
import com.jarvis.knowledge.workspace.KnowledgeToolResult;
import com.jarvis.knowledge.workspace.KnowledgeWorkspaceAuditContext;
import com.jarvis.knowledge.workspace.KnowledgeWorkspaceNode;
import com.jarvis.knowledge.workspace.KnowledgeWorkspaceService;
import com.jarvis.knowledge.workspace.KnowledgeWorkspaceTree;
import com.jarvis.knowledge.workspace.WorkspaceTransaction;
import com.jarvis.knowledge.workspace.WorkspaceTransactionManager;
import com.jarvis.tools.JarvisTool;
import com.jarvis.tools.ToolException;
import com.jarvis.tools.ToolRequest;
import com.jarvis.tools.ToolResult;
import com.jarvis.tools.schema.ToolArgumentDefinition;
import com.jarvis.tools.schema.ToolDefinition;
import com.jarvis.tools.schema.ToolOperationDefinition;
import com.jarvis.tools.schema.ToolSafetyLevel;
import com.jarvis.tools.schema.ToolSchemaProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Native cognitive tool for reading and maintaining the knowledge workspace.
 */
@Service
public class KnowledgeTool implements JarvisTool, ToolSchemaProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(KnowledgeTool.class);
    private static final String TOOL_NAME = "knowledge";

    private final KnowledgeWorkspaceService workspaceService;
    private final WorkspaceTransactionManager transactionManager;
    private final CognitiveEventBus cognitiveEventBus;

    /**
     * Creates the knowledge tool.
     */
    public KnowledgeTool(
            KnowledgeWorkspaceService workspaceService,
            WorkspaceTransactionManager transactionManager,
            CognitiveEventBus cognitiveEventBus
    ) {
        this.workspaceService = workspaceService;
        this.transactionManager = transactionManager;
        this.cognitiveEventBus = cognitiveEventBus;
    }

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public String getDescription() {
        return "Manages the J.A.R.V.I.S. logical Knowledge Workspace.";
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(TOOL_NAME, getDescription(), List.of(
                operation("READ_DOCUMENT",
                        "Read the full contents of an exact Knowledge Workspace document. Use this as soon as a "
                                + "document path is already known — for example from a previous SEARCH_CONTENT or "
                                + "LIST_TREE/LIST_FOLDER result entry such as {\"type\":\"file\",\"path\":\"hardware/graphics_card.txt\"}. "
                                + "Pass that exact \"path\" value as the path argument; do not guess or modify it.",
                        false, ToolSafetyLevel.READ, arg("path", true)),
                operation("CREATE_DOCUMENT", "Create a document by logical path and explicit model-provided content.", true, ToolSafetyLevel.WRITE, arg("path", true), arg("content", true)),
                operation("UPDATE_DOCUMENT", "Instruction-based document update using explicit model-provided instruction.", true, ToolSafetyLevel.WRITE, arg("path", true), arg("instruction", true), arg("text", false)),
                operation("APPEND_DOCUMENT", "Append explicit model-provided text to a document.", true, ToolSafetyLevel.WRITE, arg("path", true), arg("text", true)),
                operation("DELETE_DOCUMENT", "Delete a document.", true, ToolSafetyLevel.DELETE, arg("path", true)),
                operation("MOVE_DOCUMENT", "Move a document.", true, ToolSafetyLevel.WRITE, arg("path", true), arg("newParent", true)),
                operation("RENAME_DOCUMENT", "Rename a document.", true, ToolSafetyLevel.WRITE, arg("path", true), arg("newName", true)),
                operation("LIST_FOLDER",
                        "List the direct entries of one logical folder (or the root when path is omitted). "
                                + "Returns entries:[{type:\"file\"|\"folder\",path,name}]. Use the returned \"path\" verbatim "
                                + "with READ_DOCUMENT once you spot the file you need — do not keep listing further folders "
                                + "once a relevant file path is visible.",
                        false, ToolSafetyLevel.READ, arg("path", false)),
                operation("SEARCH_DOCUMENT", "Search indexed knowledge documents by title/path/metadata relevance.", false, ToolSafetyLevel.READ, arg("query", true)),
                operation("SEARCH_CONTENT",
                        "Search knowledge documents by meaning as well as keywords (hybrid lexical + semantic retrieval). "
                                + "Can find a relevant document even when the query shares no exact words with its content.",
                        false, ToolSafetyLevel.READ, arg("query", true)),
                operation("CREATE_FOLDER", "Create a logical folder.", true, ToolSafetyLevel.WRITE, arg("path", true)),
                operation("DELETE_FOLDER", "Delete a logical folder.", true, ToolSafetyLevel.DELETE, arg("path", true)),
                operation("MOVE_FOLDER", "Move a logical folder.", true, ToolSafetyLevel.WRITE, arg("path", true), arg("newParent", true)),
                operation("LIST_TREE",
                        "List every folder and document in the Knowledge Workspace, flattened as "
                                + "entries:[{type:\"file\"|\"folder\",path,name}]. Use the returned \"path\" verbatim with "
                                + "READ_DOCUMENT once you spot the file you need.",
                        false, ToolSafetyLevel.READ),
                operation("DOCUMENT_EXISTS", "Check whether a logical path exists.", false, ToolSafetyLevel.READ, arg("path", true)),
                operation("PLAN_KNOWLEDGE_UPDATE", "Plan a multi-document knowledge update before writing.", false, ToolSafetyLevel.READ, arg("query", false), arg("changes", false), arg("content", false))
        ));
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        long startedNano = System.nanoTime();
        KnowledgeToolOperation operation = operation(request);
        LOGGER.info("[TOOL] KnowledgeTool started operation={} requestId={} conversationId={}",
                operation, request.requestId(), request.conversationId());
        LOGGER.info("[KNOWLEDGE_ACCESS] requestedBy=MODEL operation={} query={} document={} requestId={}",
                operation, arg(request, "query"), arg(request, "path"), request.requestId());
        publish(request, CognitiveEventType.TOOL_STARTED, "STARTED", "KnowledgeTool started", null,
                Map.of("operation", operation.name()));

        KnowledgeWorkspaceAuditContext.start(
                request.requestId(),
                request.conversationId(),
                TOOL_NAME + "." + operation.name(),
                request.reason(),
                request.reasoningSummary()
        );
        try (WorkspaceTransaction transaction = transactionManager.begin(
                request.requestId(),
                request.conversationId(),
                TOOL_NAME + "." + operation.name(),
                request.reason(),
                request.reasoningSummary()
        )) {
            ToolResult result = executeOperation(operation, request);
            transaction.commit();
            long durationMs = (System.nanoTime() - startedNano) / 1_000_000L;
            publish(request, CognitiveEventType.TOOL_FINISHED, "FINISHED", "KnowledgeTool finished", null,
                    Map.of("operation", operation.name(), "success", result.success()));
            LOGGER.info("[TOOL] KnowledgeTool finished operation={} success={}", operation, result.success());
            LOGGER.info("[KNOWLEDGE_ACCESS] requestedBy=MODEL operation={} success={} durationMs={} requestId={}",
                    operation, result.success(), durationMs, request.requestId());
            return result;
        } catch (RuntimeException exception) {
            publish(request, CognitiveEventType.ERROR, "ERROR", "KnowledgeTool failed", null,
                    Map.of("operation", operation.name(), "error", exception.getMessage()));
            LOGGER.error("[TOOL] KnowledgeTool failed operation={} error={}", operation, exception.getMessage(), exception);
            throw exception;
        } finally {
            KnowledgeWorkspaceAuditContext.clear();
        }
    }

    private ToolResult executeOperation(KnowledgeToolOperation operation, ToolRequest request) {
        return switch (operation) {
            case READ_DOCUMENT -> readDocument(request);
            case CREATE_DOCUMENT -> createDocument(request);
            case UPDATE_DOCUMENT -> updateDocument(request);
            case APPEND_DOCUMENT -> appendDocument(request);
            case DELETE_DOCUMENT -> wrap(request, CognitiveEventType.DOCUMENT_DELETED,
                    workspaceService.delete(documentNode(arg(request, "path"))));
            case MOVE_DOCUMENT -> wrap(request, CognitiveEventType.DOCUMENT_MOVED,
                    workspaceService.move(documentNode(arg(request, "path")), folderNode(arg(request, "newParent"))));
            case RENAME_DOCUMENT -> wrap(request, CognitiveEventType.DOCUMENT_RENAMED,
                    workspaceService.rename(documentNode(arg(request, "path")), arg(request, "newName")));
            case LIST_FOLDER -> listFolder(request);
            case SEARCH_DOCUMENT, SEARCH_CONTENT -> search(request);
            case CREATE_FOLDER -> wrap(request, CognitiveEventType.FOLDER_CREATED,
                    workspaceService.createFolder(parentPath(request), leafName(request)));
            case DELETE_FOLDER -> wrap(request, CognitiveEventType.DOCUMENT_DELETED,
                    workspaceService.delete(folderNode(arg(request, "path"))));
            case MOVE_FOLDER -> wrap(request, CognitiveEventType.DOCUMENT_MOVED,
                    workspaceService.move(folderNode(arg(request, "path")), folderNode(arg(request, "newParent"))));
            case LIST_TREE -> listTree();
            case DOCUMENT_EXISTS -> wrap(request, null, workspaceService.exists(arg(request, "path")));
            case PLAN_KNOWLEDGE_UPDATE -> planKnowledgeUpdate(request);
        };
    }

    private ToolResult readDocument(ToolRequest request) {
        KnowledgeToolResult result = workspaceService.read(arg(request, "path"));
        if (!result.applied()) {
            publish(request, CognitiveEventType.DOCUMENT_READ, "NOT_FOUND", result.message(), result.nodeId(), result.data());
            LOGGER.info("[TOOL] READ_DOCUMENT not found {}", result.path());
            return failedReadResult(result);
        }
        publish(request, CognitiveEventType.DOCUMENT_READ, "READ", "Document read", result.nodeId(), result.data());
        LOGGER.info("[TOOL] READ_DOCUMENT {}", result.path());
        return wrapResult(result);
    }

    private ToolResult failedReadResult(KnowledgeToolResult result) {
        Map<String, Object> data = mergedResultData(result, result.data());
        return new ToolResult(
                false,
                TOOL_NAME,
                result.tool().replace("knowledge.", "").toUpperCase(Locale.ROOT),
                "",
                "",
                false,
                result.nodeId() == null || result.nodeId().isBlank() ? List.of() : List.of(result.nodeId()),
                result.message(),
                data,
                "DOCUMENT_NOT_FOUND",
                result.message() + ": " + result.path(),
                false,
                ""
        );
    }

    private ToolResult updateDocument(ToolRequest request) {
        LOGGER.info("[TOOL] UPDATE_DOCUMENT path={} instruction={}", arg(request, "path"), arg(request, "instruction"));
        KnowledgeToolResult result = workspaceService.updateDocument(
                arg(request, "path"),
                arg(request, "instruction"),
                arg(request, "text")
        );
        return wrap(request, CognitiveEventType.DOCUMENT_UPDATED, result);
    }

    private ToolResult createDocument(ToolRequest request) {
        return wrap(request, CognitiveEventType.DOCUMENT_CREATED,
                workspaceService.createDocument(parentPath(request), leafName(request), arg(request, "content")));
    }

    private ToolResult appendDocument(ToolRequest request) {
        return wrap(request, CognitiveEventType.DOCUMENT_UPDATED,
                workspaceService.appendDocument(arg(request, "path"), arg(request, "text")));
    }

    private ToolResult listFolder(ToolRequest request) {
        KnowledgeWorkspaceTree tree = workspaceService.list();
        String path = arg(request, "path");
        KnowledgeWorkspaceNode node = path.isBlank()
                ? tree.root()
                : findNode(tree.root(), path).orElse(tree.root());
        return success("Folder listed", Map.of("path", path, "entries", flatten(node, false)));
    }

    private ToolResult listTree() {
        KnowledgeWorkspaceTree tree = workspaceService.list();
        return success("Knowledge tree", Map.of("entries", flatten(tree.root(), true)));
    }

    /**
     * Flattens a workspace node's children into a simple, tool-agnostic list the model can copy
     * an exact "path" from directly into READ_DOCUMENT, instead of a raw recursive node object.
     *
     * @param node node whose children are listed
     * @param recursive true to include the full subtree, false for direct children only
     * @return flattened entries
     */
    private List<Map<String, Object>> flatten(KnowledgeWorkspaceNode node, boolean recursive) {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (KnowledgeWorkspaceNode child : node.children()) {
            entries.add(Map.of(
                    "type", child.type() == KnowledgeNodeType.DOCUMENT ? "file" : "folder",
                    "path", child.relativePath(),
                    "name", child.name()
            ));
            if (recursive && child.type() != KnowledgeNodeType.DOCUMENT) {
                entries.addAll(flatten(child, true));
            }
        }
        return entries;
    }

    private ToolResult search(ToolRequest request) {
        String query = arg(request, "query");
        LOGGER.info("[TOOL] SEARCH_CONTENT query=\"{}\"", query);
        publish(request, CognitiveEventType.SEARCH_STARTED, "SEARCHING", "Knowledge search started", null,
                Map.of("query", query));
        RetrievalResult result = workspaceService.search(query);
        for (RetrievalDocument document : result.documents()) {
            publish(request, CognitiveEventType.SEARCH_RESULT, "FOUND", "Knowledge search result",
                    documentNode(document.relativePath()), Map.of(
                            "documentId", document.documentId().toString(),
                            "path", document.relativePath(),
                            "title", document.title(),
                            "score", document.score()
                    ));
        }
        publish(request, CognitiveEventType.SEARCH_FINISHED, "FINISHED", "Knowledge search finished", null, Map.of(
                "query", result.query(),
                "documentsScanned", result.documentsScanned(),
                "resultsReturned", result.documents().size(),
                "executionTimeMs", result.executionTimeMs()
        ));
        return success("Search finished", Map.of("result", result));
    }

    private ToolResult planKnowledgeUpdate(ToolRequest request) {
        String query = firstNonBlank(arg(request, "query"), arg(request, "content"), request.reason());
        RetrievalResult retrieval = workspaceService.search(query);
        List<Map<String, Object>> candidates = retrieval.documents().stream()
                .limit(5)
                .map(document -> Map.<String, Object>of(
                        "path", document.relativePath(),
                        "title", document.title(),
                        "score", document.score()
                ))
                .toList();
        Map<String, Object> plan = Map.of(
                "title", "LLM-owned knowledge planning context",
                "readyToExecute", false,
                "query", query,
                "candidateDocuments", candidates,
                "instruction", "The LLM must decide the final operation, target path, and document content in a separate TOOL_CALL."
        );
        LOGGER.info("[TOOL] PLAN_KNOWLEDGE_UPDATE query=\"{}\" candidates={}", query, candidates.size());
        return success("Knowledge planning context prepared", Map.of("plan", plan));
    }

    private ToolResult wrap(ToolRequest request, CognitiveEventType eventType, KnowledgeToolResult result) {
        if (eventType != null) {
            publish(request, eventType, result.applied() ? "OK" : result.draft() ? "DRAFT" : "SKIPPED",
                    result.message(), result.nodeId(), result.data());
        }
        return wrapResult(result);
    }

    private ToolResult wrapResult(KnowledgeToolResult result) {
        return wrapResult(result, result.data());
    }

    private ToolResult wrapResult(KnowledgeToolResult result, Map<String, Object> data) {
        boolean requiresApproval = Boolean.parseBoolean(String.valueOf(data.getOrDefault("requiresApproval", result.draft())));
        String draftId = String.valueOf(data.getOrDefault("draftId", ""));
        return new ToolResult(
                true,
                TOOL_NAME,
                result.tool().replace("knowledge.", "").toUpperCase(Locale.ROOT),
                "",
                "",
                result.applied(),
                result.nodeId() == null || result.nodeId().isBlank() ? List.of() : List.of(result.nodeId()),
                result.message(),
                mergedResultData(result, data),
                "",
                "",
                requiresApproval,
                draftId
        );
    }

    private ToolResult success(String output, Map<String, Object> metadata) {
        return new ToolResult(true, TOOL_NAME, "", "", "", false, List.of(), output, metadata, "", "", false, "");
    }

    private Map<String, Object> mergedResultData(KnowledgeToolResult result, Map<String, Object> data) {
        Map<String, Object> values = new HashMap<>();
        values.put("applied", result.applied());
        values.put("draft", result.draft());
        values.put("nodeId", empty(result.nodeId()));
        values.put("path", empty(result.path()));
        values.put("timestamp", result.timestamp().toString());
        values.put("data", data == null ? Map.of() : data);
        if (data != null) {
            values.putAll(data);
        }
        return values;
    }

    private ToolOperationDefinition operation(
            String name,
            String description,
            boolean write,
            ToolSafetyLevel safetyLevel,
            ToolArgumentDefinition... arguments
    ) {
        return new ToolOperationDefinition(name, description, List.of(arguments), write, safetyLevel);
    }

    private ToolArgumentDefinition arg(String name, boolean required) {
        return new ToolArgumentDefinition(name, name.equals("changes") ? "array" : "string", required, "");
    }

    private KnowledgeToolOperation operation(ToolRequest request) {
        if (request == null) {
            throw new ToolException("Tool request is required");
        }
        try {
            return KnowledgeToolOperation.valueOf(empty(request.operation()).trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new ToolException("Unsupported knowledge operation: " + request.operation(), exception);
        }
    }

    private Optional<KnowledgeWorkspaceNode> findNode(KnowledgeWorkspaceNode node, String path) {
        if (node.relativePath().equals(path) || node.nodeId().equals(path) || node.nodeId().equals(folderNode(path))) {
            return Optional.of(node);
        }
        return node.children().stream()
                .map(child -> findNode(child, path))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst();
    }

    private void publish(
            ToolRequest request,
            CognitiveEventType eventType,
            String status,
            String message,
            String nodeId,
            Map<String, Object> metadata
    ) {
        Map<String, Object> values = new HashMap<>(metadata == null ? Map.of() : metadata);
        values.put("tool", TOOL_NAME);
        values.put("operation", empty(request.operation()));
        values.put("requestId", empty(request.requestId()));
        values.put("conversationId", empty(request.conversationId()));
        cognitiveEventBus.publish(eventType, status, message, nodeId, values);
        cognitiveEventBus.publishBackground(request.requestId(), request.conversationId(), eventType, status, message, nodeId, values);
    }

    private String arg(ToolRequest request, String name) {
        Object value = request.arguments().get(name);
        return value == null ? "" : String.valueOf(value);
    }

    private String parentPath(ToolRequest request) {
        String explicitParent = arg(request, "parentPath");
        if (!explicitParent.isBlank()) {
            return explicitParent;
        }
        String path = stripSlash(firstNonBlank(arg(request, "path"), arg(request, "name")));
        int separator = path.lastIndexOf('/');
        return separator < 0 ? "" : path.substring(0, separator);
    }

    private String leafName(ToolRequest request) {
        String name = arg(request, "name");
        if (!name.isBlank() && !name.contains("/")) {
            return name;
        }
        String path = stripSlash(firstNonBlank(arg(request, "path"), name));
        int separator = path.lastIndexOf('/');
        return separator < 0 ? path : path.substring(separator + 1);
    }

    private String documentNode(String path) {
        return "knowledge-document:" + stripSlash(path);
    }

    private String folderNode(String path) {
        String cleaned = stripSlash(path);
        return cleaned.isBlank() ? "knowledge-root:/" : "knowledge-folder:" + cleaned;
    }

    private String stripSlash(String value) {
        String cleaned = empty(value).replace('\\', '/');
        while (cleaned.startsWith("/")) {
            cleaned = cleaned.substring(1);
        }
        return cleaned;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String empty(String value) {
        return value == null ? "" : value;
    }
}
