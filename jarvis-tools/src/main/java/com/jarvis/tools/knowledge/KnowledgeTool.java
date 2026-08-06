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
import com.jarvis.tools.schema.ToolArgumentDefinition;
import com.jarvis.tools.schema.ToolDefinition;
import com.jarvis.tools.schema.ToolOperationDefinition;
import com.jarvis.tools.schema.ToolSafetyLevel;
import com.jarvis.tools.schema.ToolSchemaProvider;
import com.jarvis.tools.knowledge.filing.ExtractedKnowledge;
import com.jarvis.tools.knowledge.filing.KnowledgeDestinationPlan;
import com.jarvis.tools.knowledge.filing.KnowledgeDestinationPlanner;
import com.jarvis.tools.knowledge.filing.KnowledgeExtractionService;
import com.jarvis.tools.knowledge.filing.KnowledgeFilingProperties;
import com.jarvis.tools.knowledge.filing.KnowledgeKind;
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
public class KnowledgeTool implements JarvisTool, ToolSchemaProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(KnowledgeTool.class);
    private static final String TOOL_NAME = "knowledge";

    private final KnowledgeWorkspaceService workspaceService;
    private final WorkspaceTransactionManager transactionManager;
    private final CognitiveEventBus cognitiveEventBus;
    private final KnowledgeExtractionService extractionService;
    private final KnowledgeDestinationPlanner destinationPlanner;
    private final KnowledgeFilingProperties filingProperties;

    /**
     * Creates the knowledge tool.
     */
    public KnowledgeTool(
            KnowledgeWorkspaceService workspaceService,
            WorkspaceTransactionManager transactionManager,
            CognitiveEventBus cognitiveEventBus,
            KnowledgeExtractionService extractionService,
            KnowledgeDestinationPlanner destinationPlanner,
            KnowledgeFilingProperties filingProperties
    ) {
        this.workspaceService = workspaceService;
        this.transactionManager = transactionManager;
        this.cognitiveEventBus = cognitiveEventBus;
        this.extractionService = extractionService;
        this.destinationPlanner = destinationPlanner;
        this.filingProperties = filingProperties;
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
                operation("READ_DOCUMENT", "Read a document by logical path.", false, ToolSafetyLevel.READ, arg("path", true)),
                operation("CREATE_DOCUMENT", "Create a document by logical path.", true, ToolSafetyLevel.WRITE, arg("path", true), arg("content", true), arg("sourceMessage", false)),
                operation("UPDATE_DOCUMENT", "Instruction-based document update.", true, ToolSafetyLevel.WRITE, arg("path", true), arg("instruction", true), arg("text", false), arg("sourceMessage", false)),
                operation("APPEND_DOCUMENT", "Append text to a document.", true, ToolSafetyLevel.WRITE, arg("path", true), arg("text", true), arg("sourceMessage", false)),
                operation("DELETE_DOCUMENT", "Delete a document.", true, ToolSafetyLevel.DELETE, arg("path", true)),
                operation("MOVE_DOCUMENT", "Move a document.", true, ToolSafetyLevel.WRITE, arg("path", true), arg("newParent", true)),
                operation("RENAME_DOCUMENT", "Rename a document.", true, ToolSafetyLevel.WRITE, arg("path", true), arg("newName", true)),
                operation("LIST_FOLDER", "List one logical folder.", false, ToolSafetyLevel.READ, arg("path", false)),
                operation("SEARCH_DOCUMENT", "Search indexed knowledge documents.", false, ToolSafetyLevel.READ, arg("query", true)),
                operation("SEARCH_CONTENT", "Search knowledge content using the configured retriever.", false, ToolSafetyLevel.READ, arg("query", true)),
                operation("CREATE_FOLDER", "Create a logical folder.", true, ToolSafetyLevel.WRITE, arg("path", true)),
                operation("DELETE_FOLDER", "Delete a logical folder.", true, ToolSafetyLevel.DELETE, arg("path", true)),
                operation("MOVE_FOLDER", "Move a logical folder.", true, ToolSafetyLevel.WRITE, arg("path", true), arg("newParent", true)),
                operation("LIST_TREE", "List the knowledge tree.", false, ToolSafetyLevel.READ),
                operation("DOCUMENT_EXISTS", "Check whether a logical path exists.", false, ToolSafetyLevel.READ, arg("path", true)),
                operation("PLAN_KNOWLEDGE_UPDATE", "Plan a multi-document knowledge update before writing.", false, ToolSafetyLevel.READ, arg("query", false), arg("changes", false), arg("content", false))
        ));
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
        if (shouldFileKnowledge(request)) {
            return fileKnowledge(request);
        }
        LOGGER.info("[TOOL] UPDATE_DOCUMENT path={} instruction={}", arg(request, "path"), arg(request, "instruction"));
        KnowledgeToolResult result = workspaceService.updateDocument(
                arg(request, "path"),
                arg(request, "instruction"),
                arg(request, "text")
        );
        return wrap(request, CognitiveEventType.DOCUMENT_UPDATED, result);
    }

    private ToolResult createDocument(ToolRequest request) {
        if (shouldFileKnowledge(request)) {
            return fileKnowledge(request);
        }
        return wrap(request, CognitiveEventType.DOCUMENT_CREATED,
                workspaceService.createDocument(parentPath(request), leafName(request), arg(request, "content")));
    }

    private ToolResult appendDocument(ToolRequest request) {
        if (shouldFileKnowledge(request)) {
            return fileKnowledge(request);
        }
        return wrap(request, CognitiveEventType.DOCUMENT_UPDATED,
                workspaceService.appendDocument(arg(request, "path"), arg(request, "text")));
    }

    private ToolResult fileKnowledge(ToolRequest request) {
        String source = firstNonBlank(arg(request, "sourceMessage"), arg(request, "content"), arg(request, "text"));
        publish(request, CognitiveEventType.KNOWLEDGE_EXTRACTION_STARTED, "STARTED", "Extracting knowledge", null,
                Map.of("sourceLength", source.length()));
        ExtractedKnowledge extracted = extractionService.extract(source);
        publish(request, CognitiveEventType.KNOWLEDGE_EXTRACTION_FINISHED, "FINISHED", "Knowledge extracted", null,
                extractionMetadata(extracted));

        publish(request, CognitiveEventType.KNOWLEDGE_TREE_INSPECTION_STARTED, "STARTED", "Inspecting knowledge tree", null,
                Map.of("subject", extracted.subject()));
        KnowledgeWorkspaceTree tree = workspaceService.list();
        RetrievalResult searchResult = workspaceService.search(firstNonBlank(extracted.subject(), extracted.normalizedFact()));
        publish(request, CognitiveEventType.KNOWLEDGE_TREE_INSPECTION_FINISHED, "FINISHED", "Knowledge tree inspected", null,
                Map.of("documents", tree.documents(), "folders", tree.folders(), "candidates", searchResult.documents().size()));

        KnowledgeDestinationPlan plan = destinationPlanner.plan(extracted, tree, searchResult);
        publish(request, CognitiveEventType.KNOWLEDGE_DESTINATION_PLANNED, "PLANNED", "Knowledge destination planned",
                documentNode(plan.targetPath()), planMetadata(extracted, plan));
        if (!plan.existingDocumentId().isBlank()) {
            publish(request, CognitiveEventType.KNOWLEDGE_DUPLICATE_FOUND, "FOUND", "Existing knowledge document found",
                    documentNode(plan.targetPath()), planMetadata(extracted, plan));
        }

        if ("SKIP".equalsIgnoreCase(plan.operation())) {
            return new ToolResult(true, TOOL_NAME, "SKIP", request.requestId(), request.conversationId(), false, List.of(),
                    "Knowledge skipped", Map.of("extractedKnowledge", extractionMetadata(extracted), "plan", planMetadata(extracted, plan)),
                    "", "", false, "");
        }

        publish(request, CognitiveEventType.KNOWLEDGE_WRITE_STARTED, "STARTED", "Knowledge write started",
                documentNode(plan.targetPath()), planMetadata(extracted, plan));
        KnowledgeToolResult result;
        if ("UPDATE_DOCUMENT".equalsIgnoreCase(plan.operation())) {
            result = workspaceService.updateDocument(plan.targetPath(), "SET_SECTION:" + plan.section(), sectionBody(extracted, plan));
        } else {
            result = workspaceService.createDocument(parentOf(plan.targetPath()), leafOf(plan.targetPath()), documentContent(extracted, plan));
        }
        CognitiveEventType event = "UPDATE_DOCUMENT".equalsIgnoreCase(plan.operation())
                ? CognitiveEventType.DOCUMENT_UPDATED
                : CognitiveEventType.DOCUMENT_CREATED;
        publish(request, CognitiveEventType.KNOWLEDGE_WRITE_FINISHED, result.applied() ? "OK" : result.draft() ? "DRAFT" : "SKIPPED",
                result.message(), result.nodeId(), mergedData(result.data(), extracted, plan));
        publish(request, event, result.applied() ? "OK" : result.draft() ? "DRAFT" : "SKIPPED", result.message(),
                result.nodeId(), mergedData(result.data(), extracted, plan));
        ToolResult toolResult = wrapResult(result, mergedData(result.data(), extracted, plan));
        publish(request, CognitiveEventType.KNOWLEDGE_WRITE_VERIFIED, result.applied() ? "VERIFIED" : result.draft() ? "DRAFT" : "SKIPPED",
                result.draft() ? "Knowledge draft prepared" : "Knowledge write verified", result.nodeId(), toolResult.data());
        return toolResult;
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

    private boolean shouldFileKnowledge(ToolRequest request) {
        if (!filingProperties.isEnabled()) {
            return false;
        }
        String source = firstNonBlank(arg(request, "sourceMessage"), arg(request, "content"), arg(request, "text"));
        if (source.isBlank() || filingProperties.storeRawMessages()) {
            return false;
        }
        String lower = source.toLowerCase(Locale.ROOT);
        return request.arguments().containsKey("sourceMessage")
                || lower.contains("zapisz")
                || lower.contains("zapam")
                || lower.contains("utw")
                || lower.contains("stw")
                || lower.contains("dodaj");
    }

    private String documentContent(ExtractedKnowledge extracted, KnowledgeDestinationPlan plan) {
        StringBuilder builder = new StringBuilder();
        builder.append("# ").append(plan.documentTitle().isBlank() ? extracted.subject() : plan.documentTitle()).append(System.lineSeparator())
                .append(System.lineSeparator())
                .append("## ").append(plan.section()).append(System.lineSeparator())
                .append(System.lineSeparator())
                .append(sectionBody(extracted, plan)).append(System.lineSeparator());
        return builder.toString();
    }

    private String sectionBody(ExtractedKnowledge extracted, KnowledgeDestinationPlan plan) {
        if (extracted.kind() == KnowledgeKind.BIRTHDAY) {
            return extracted.value();
        }
        if (extracted.kind() == KnowledgeKind.HARDWARE || extracted.kind() == KnowledgeKind.DEVICE) {
            StringBuilder builder = new StringBuilder();
            for (String part : extracted.value().split(";\\s*")) {
                if (!part.isBlank()) {
                    builder.append("- ").append(hardwareLine(part)).append(System.lineSeparator());
                }
            }
            return builder.toString().stripTrailing();
        }
        return extracted.normalizedFact();
    }

    private String hardwareLine(String part) {
        String lower = part.toLowerCase(Locale.ROOT);
        if (lower.contains("rtx") || lower.contains("gtx") || lower.contains("gpu") || lower.contains("aorus")) {
            return "GPU: " + part;
        }
        if (lower.contains("ram") || lower.matches(".*\\b\\d+\\s*gb\\b.*")) {
            return "RAM: " + part;
        }
        if (lower.contains("i5") || lower.contains("i7") || lower.contains("ryzen") || lower.contains("cpu")) {
            return "CPU: " + part;
        }
        return part;
    }

    private Map<String, Object> extractionMetadata(ExtractedKnowledge extracted) {
        Map<String, Object> values = new HashMap<>();
        values.put("subject", extracted.subject());
        values.put("relation", extracted.relation());
        values.put("value", extracted.value());
        values.put("normalizedFact", extracted.normalizedFact());
        values.put("kind", extracted.kind().name());
        values.put("entities", extracted.entities());
        values.put("tags", extracted.tags());
        values.put("language", extracted.language());
        values.put("confidence", extracted.confidence());
        values.put("worthSaving", extracted.worthSaving());
        return values;
    }

    private Map<String, Object> planMetadata(ExtractedKnowledge extracted, KnowledgeDestinationPlan plan) {
        Map<String, Object> values = new HashMap<>();
        values.put("operation", plan.operation());
        values.put("targetPath", plan.targetPath());
        values.put("path", plan.targetPath());
        values.put("existingDocumentId", plan.existingDocumentId());
        values.put("documentTitle", plan.documentTitle());
        values.put("section", plan.section());
        values.put("reason", plan.reason());
        values.put("confidence", plan.confidence());
        values.put("alternatives", plan.alternatives());
        values.put("normalizedFact", extracted.normalizedFact());
        values.put("extractedFact", extracted.normalizedFact());
        values.put("subject", extracted.subject());
        values.put("kind", extracted.kind().name());
        values.put("documentPreview", documentContent(extracted, plan));
        return values;
    }

    private Map<String, Object> mergedData(Map<String, Object> base, ExtractedKnowledge extracted, KnowledgeDestinationPlan plan) {
        Map<String, Object> values = new HashMap<>(base == null ? Map.of() : base);
        values.putAll(planMetadata(extracted, plan));
        values.put("extractedKnowledge", extractionMetadata(extracted));
        return values;
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

    private String parentOf(String path) {
        String cleaned = stripSlash(path);
        int separator = cleaned.lastIndexOf('/');
        return separator < 0 ? "" : cleaned.substring(0, separator);
    }

    private String leafOf(String path) {
        String cleaned = stripSlash(path);
        int separator = cleaned.lastIndexOf('/');
        return separator < 0 ? cleaned : cleaned.substring(separator + 1);
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
