package com.jarvis.tools.knowledge;

import com.jarvis.common.event.CognitiveEventBus;
import com.jarvis.common.event.CognitiveEventType;
import com.jarvis.knowledge.retrieval.RetrievalDocument;
import com.jarvis.knowledge.retrieval.RetrievalResult;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Native cognitive tool for reading and maintaining the knowledge workspace.
 */
@Service
public class KnowledgeTool implements JarvisTool {

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
    public ToolResult execute(ToolRequest request) {
        KnowledgeToolOperation operation = operation(request);
        LOGGER.info("[TOOL] KnowledgeTool started operation={} requestId={} conversationId={}",
                operation, request.requestId(), request.conversationId());
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
            publish(request, CognitiveEventType.TOOL_FINISHED, "FINISHED", "KnowledgeTool finished", null,
                    Map.of("operation", operation.name(), "success", result.success()));
            LOGGER.info("[TOOL] KnowledgeTool finished operation={} success={}", operation, result.success());
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
            case CREATE_DOCUMENT -> wrap(request, CognitiveEventType.DOCUMENT_CREATED,
                    workspaceService.createDocument(parentPath(request), leafName(request), arg(request, "content")));
            case UPDATE_DOCUMENT -> updateDocument(request);
            case APPEND_DOCUMENT -> wrap(request, CognitiveEventType.DOCUMENT_UPDATED,
                    workspaceService.appendDocument(arg(request, "path"), arg(request, "text")));
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
            case LIST_TREE -> success("Knowledge tree", Map.of("tree", workspaceService.list()));
            case DOCUMENT_EXISTS -> wrap(request, null, workspaceService.exists(arg(request, "path")));
            case PLAN_KNOWLEDGE_UPDATE -> planKnowledgeUpdate(request);
        };
    }

    private ToolResult readDocument(ToolRequest request) {
        KnowledgeToolResult result = workspaceService.read(arg(request, "path"));
        publish(request, CognitiveEventType.DOCUMENT_READ, "READ", "Document read", result.nodeId(), result.data());
        LOGGER.info("[TOOL] READ_DOCUMENT {}", result.path());
        return wrapResult(result);
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

    private ToolResult listFolder(ToolRequest request) {
        KnowledgeWorkspaceTree tree = workspaceService.list();
        String path = arg(request, "path");
        Object output = path.isBlank()
                ? tree
                : findNode(tree.root(), path).orElse(tree.root());
        return success("Folder listed", Map.of("path", path, "node", output));
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
        List<Map<String, Object>> steps = retrieval.documents().stream()
                .limit(5)
                .map(document -> {
                    Map<String, Object> step = new HashMap<>();
                    step.put("path", document.relativePath());
                    step.put("operation", "UPDATE_DOCUMENT");
                    step.put("instruction", firstNonBlank(arg(request, "instruction"), "Review and update relevant section"));
                    step.put("score", document.score());
                    step.put("reason", "Relevant existing document found");
                    return step;
                })
                .toList();
        Map<String, Object> plan = Map.of(
                "title", "Knowledge Plan",
                "readyToExecute", true,
                "query", query,
                "steps", steps,
                "missingTargetPolicy", "Create a focused document only when no existing document is relevant"
        );
        LOGGER.info("[TOOL] PLAN_KNOWLEDGE_UPDATE query=\"{}\" steps={}", query, steps.size());
        return success("Knowledge update plan created", Map.of("plan", plan));
    }

    private ToolResult wrap(ToolRequest request, CognitiveEventType eventType, KnowledgeToolResult result) {
        if (eventType != null) {
            publish(request, eventType, result.applied() ? "OK" : result.draft() ? "DRAFT" : "SKIPPED",
                    result.message(), result.nodeId(), result.data());
        }
        return wrapResult(result);
    }

    private ToolResult wrapResult(KnowledgeToolResult result) {
        return new ToolResult(true, result.message(), Map.of(
                "applied", result.applied(),
                "draft", result.draft(),
                "nodeId", empty(result.nodeId()),
                "path", empty(result.path()),
                "timestamp", result.timestamp().toString(),
                "data", result.data()
        ));
    }

    private ToolResult success(String output, Map<String, Object> metadata) {
        return new ToolResult(true, output, metadata);
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
