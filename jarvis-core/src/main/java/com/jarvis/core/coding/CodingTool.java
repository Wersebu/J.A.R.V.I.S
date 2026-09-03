package com.jarvis.core.coding;

import com.jarvis.api.service.CodingService;
import com.jarvis.tools.JarvisTool;
import com.jarvis.tools.ToolException;
import com.jarvis.tools.ToolRequest;
import com.jarvis.tools.ToolResult;
import com.jarvis.tools.schema.ToolArgumentDefinition;
import com.jarvis.tools.schema.ToolDefinition;
import com.jarvis.tools.schema.ToolJsonSchema;
import com.jarvis.tools.schema.ToolOperationDefinition;
import com.jarvis.tools.schema.ToolSafetyLevel;
import com.jarvis.tools.schema.ToolSchemaProvider;
import com.jarvis.tools.workflow.ToolOperationClassifier;
import com.jarvis.tools.workflow.ToolOperationRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.TreeMap;

/**
 * Model-facing Coding Agent tool backed by {@link CodingService}.
 */
@Service
public class CodingTool implements JarvisTool, ToolSchemaProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(CodingTool.class);
    private static final String TOOL_NAME = "coding";

    private final CodingService codingService;

    public CodingTool(CodingService codingService) {
        this.codingService = codingService;
    }

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public String getDescription() {
        return "Reads, searches, edits, builds, tests, and inspects the user-selected Coding Workspace project. "
                + "This is for project files on the active Coding Workspace, not the Knowledge Workspace.";
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(TOOL_NAME, getDescription(), List.of(
                operation("WORKSPACE_INSPECT", "Inspect the active Coding Workspace metadata and build/Git state. No workspaceId argument is accepted; Core injects the user-selected workspace.", false, ToolSafetyLevel.READ),
                operation("FILE_LIST", "List project files/directories inside the active Coding Workspace. Use for project structure. This is not KnowledgeTool.", false, ToolSafetyLevel.READ, arg("path", false)),
                operation("FILE_SEARCH", "Search text inside project files in the active Coding Workspace. Use for source code and project files, including WINDOWS_ONLY.txt. This is not the knowledge database.", false, ToolSafetyLevel.READ, arg("query", true), boolArg("regex", false), intArg("maxResults", false)),
                operation("FILE_READ", "Read a project file from the active Coding Workspace by relative path. Use this for source files and project files; do not use KnowledgeTool for workspace files.", false, ToolSafetyLevel.READ, arg("path", true), intArg("startLine", false), intArg("endLine", false)),
                operation("FILE_WRITE", "Write full content to a project file in the active Coding Workspace through CodingService safety checks.", true, ToolSafetyLevel.WRITE, arg("path", true), arg("content", true)),
                operation("FILE_PATCH", "Patch a project file by replacing an exact expected text block with replacement text through CodingService safety checks.", true, ToolSafetyLevel.WRITE, arg("path", true), arg("expected", true), arg("replacement", true)),
                operation("DIRECTORY_CREATE", "Create a directory in the active Coding Workspace through CodingService safety checks.", true, ToolSafetyLevel.WRITE, arg("path", true)),
                operation("FILE_MOVE", "Move or rename a file inside the active Coding Workspace through CodingService safety checks.", true, ToolSafetyLevel.WRITE, arg("sourcePath", true), arg("targetPath", true)),
                operation("FILE_DELETE", "Delete a file inside the active Coding Workspace only when the user explicitly approved deletion.", true, ToolSafetyLevel.DELETE, arg("path", true), boolArg("approved", true)),
                operation("GIT_STATUS", "Read Git branch, commit, and short status for the active Coding Workspace.", false, ToolSafetyLevel.READ),
                operation("GIT_DIFF", "Read Git diff for the active Coding Workspace.", false, ToolSafetyLevel.READ),
                operation("BUILD_DETECT", "Detect build systems and default build/test commands in the active Coding Workspace.", false, ToolSafetyLevel.READ),
                operation("COMMAND_START", "Run a safe command in the active Coding Workspace through CodingService command policy.", true, ToolSafetyLevel.WRITE, arg("command", true), intArg("timeoutSeconds", false), intArg("maxOutputCharacters", false)),
                operation("COMMAND_POLL", "Poll an asynchronous command previously started in the active Coding Workspace.", false, ToolSafetyLevel.READ, arg("processId", true)),
                operation("COMMAND_CANCEL", "Cancel an asynchronous command previously started in the active Coding Workspace.", true, ToolSafetyLevel.WRITE, arg("processId", true)),
                operation("BUILD_RUN", "Run the detected or supplied build command in the active Coding Workspace through CodingService.", true, ToolSafetyLevel.WRITE, arg("command", false), intArg("timeoutSeconds", false), intArg("maxOutputCharacters", false)),
                operation("TEST_RUN", "Run the detected or supplied test command in the active Coding Workspace through CodingService.", true, ToolSafetyLevel.WRITE, arg("command", false), intArg("timeoutSeconds", false), intArg("maxOutputCharacters", false)),
                operation("BROWSER_LIST_TABS", "List inspectable tabs/pages of a browser or Electron/CEF process on the Windows host that was launched with --remote-debugging-port (e.g. a Steam-distributed browser game). Use this first to find a tab id, or to confirm the target is reachable at all.", false, ToolSafetyLevel.READ, intArg("port", false)),
                operation("BROWSER_EVALUATE", "Run JavaScript inside a Chrome DevTools Protocol target's page context and return the result. Use this to read live game state (variables, objects on window) or call functions - direct access to the actual running game instead of guessing from local files.", false, ToolSafetyLevel.READ, intArg("port", false), arg("tabId", false), arg("expression", true), intArg("timeoutSeconds", false)),
                operation("BROWSER_CONSOLE_LOGS", "Listen for console.log/warn/error output from a Chrome DevTools Protocol target for a short window and return what was captured.", false, ToolSafetyLevel.READ, intArg("port", false), arg("tabId", false), intArg("captureSeconds", false)),
                operation("BROWSER_SCREENSHOT_DESCRIBE", "Capture a screenshot of a Chrome DevTools Protocol target and answer a specific question about it using a dedicated vision model (you do not have vision yourself - a raw screenshot is useless to you). Ask a precise, targeted question, e.g. 'describe precisely the middle section of the screen' or 'list every button visible in the top bar and its label' - never a vague 'what do you see'.", false, ToolSafetyLevel.READ, intArg("port", false), arg("tabId", false), arg("question", true))
        ));
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        String workspaceId = string(request.arguments().get("_activeCodingWorkspaceId"));
        if (workspaceId.isBlank()) {
            return failure(request, "NO_ACTIVE_CODING_WORKSPACE",
                    "Wybierz workspace w zakladce Kod i przypisz go do aktualnej rozmowy.");
        }
        CodingOperation operation = operation(request.operation());
        LOGGER.info("[CODING_TOOL] selectedTool=coding_{} requestId={} conversationId={} workspaceId={}",
                operation.name().toLowerCase(Locale.ROOT), request.requestId(), request.conversationId(), workspaceId);
        try {
            ApprovalDecision approval = approvalDecision(operation, request);
            if (approval.required()) {
                CodingService.CodingApproval created = codingService.requestApproval(
                        approval.taskId(),
                        operation.name(),
                        approval.description(),
                        approval.riskLevel(),
                        approval.argumentsDigest()
                );
                return approvalRequired(request, operation, created);
            }
            Object data = executeOperation(operation, workspaceId, request);
            return success(request, operation, data, changed(operation));
        } catch (RuntimeException exception) {
            String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            LOGGER.warn("[CODING_TOOL] failed operation={} requestId={} workspaceId={} error={}",
                    operation, request.requestId(), workspaceId, message);
            return failure(request, "CODING_TOOL_FAILED", message);
        }
    }

    private Object executeOperation(CodingOperation operation, String workspaceId, ToolRequest request) {
        return switch (operation) {
            case WORKSPACE_INSPECT -> codingService.refreshWorkspace(workspaceId);
            case FILE_LIST -> codingService.listFiles(workspaceId, stringArg(request, "path"));
            case FILE_SEARCH -> codingService.search(workspaceId, new CodingService.FileSearchRequest(
                    stringArg(request, "query"),
                    boolArg(request, "regex"),
                    intArg(request, "maxResults")
            ));
            case FILE_READ -> codingService.readFile(workspaceId, stringArg(request, "path"),
                    nullableIntArg(request, "startLine"), nullableIntArg(request, "endLine"));
            case FILE_WRITE -> codingService.writeFile(workspaceId, new CodingService.FileWriteRequest(
                    stringArg(request, "path"), stringArg(request, "content")));
            case FILE_PATCH -> codingService.patchFile(workspaceId, new CodingService.PatchRequest(
                    stringArg(request, "path"), stringArg(request, "expected"), stringArg(request, "replacement")));
            case DIRECTORY_CREATE -> codingService.createDirectory(workspaceId,
                    new CodingService.DirectoryCreateRequest(stringArg(request, "path")));
            case FILE_MOVE -> codingService.moveFile(workspaceId, new CodingService.FileMoveRequest(
                    stringArg(request, "sourcePath"), stringArg(request, "targetPath")));
            case FILE_DELETE -> {
                codingService.deleteFile(workspaceId, new CodingService.FileDeleteRequest(
                        stringArg(request, "path"), boolArg(request, "approved")));
                yield Map.of("deleted", true, "path", stringArg(request, "path"));
            }
            case GIT_STATUS -> {
                CodingService.GitSnapshot snapshot = codingService.gitSnapshot(workspaceId);
                yield Map.of("branch", snapshot.branch(), "headCommit", snapshot.headCommit(), "status", snapshot.status());
            }
            case GIT_DIFF -> codingService.gitSnapshot(workspaceId);
            case BUILD_DETECT -> codingService.buildDetect(workspaceId);
            case COMMAND_START -> codingService.runCommand(workspaceId, new CodingService.CommandRequest(
                    stringArg(request, "command"), intArg(request, "timeoutSeconds"), intArg(request, "maxOutputCharacters")));
            case COMMAND_POLL -> codingService.commandPoll(workspaceId, stringArg(request, "processId"));
            case COMMAND_CANCEL -> codingService.commandCancel(workspaceId, stringArg(request, "processId"));
            case BUILD_RUN -> codingService.buildRun(workspaceId, new CodingService.BuildRunRequest(
                    stringArg(request, "command"), intArg(request, "timeoutSeconds"), intArg(request, "maxOutputCharacters")));
            case TEST_RUN -> codingService.testRun(workspaceId, new CodingService.BuildRunRequest(
                    stringArg(request, "command"), intArg(request, "timeoutSeconds"), intArg(request, "maxOutputCharacters")));
            case BROWSER_LIST_TABS -> codingService.browserListTabs(workspaceId, intArg(request, "port"));
            case BROWSER_EVALUATE -> codingService.browserEvaluate(workspaceId, new CodingService.BrowserEvaluateRequest(
                    intArg(request, "port"), stringArg(request, "tabId"), stringArg(request, "expression"), intArg(request, "timeoutSeconds")));
            case BROWSER_CONSOLE_LOGS -> codingService.browserConsoleLogs(workspaceId, new CodingService.BrowserConsoleLogsRequest(
                    intArg(request, "port"), stringArg(request, "tabId"), intArg(request, "captureSeconds")));
            case BROWSER_SCREENSHOT_DESCRIBE -> codingService.browserScreenshotDescribe(workspaceId, new CodingService.BrowserScreenshotDescribeRequest(
                    intArg(request, "port"), stringArg(request, "tabId"), stringArg(request, "question")));
        };
    }

    private ToolResult success(ToolRequest request, CodingOperation operation, Object data, boolean changed) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("workspaceId", string(request.arguments().get("_activeCodingWorkspaceId")));
        values.put("workspaceName", string(request.arguments().get("_activeCodingWorkspaceName")));
        values.put("result", data);
        return new ToolResult(true, TOOL_NAME, operation.name(), request.requestId(), request.conversationId(),
                changed, List.of("coding-workspace:" + values.get("workspaceId")),
                "Coding " + operation.name() + " finished", values, "", "", false, "");
    }

    private ToolResult failure(ToolRequest request, String code, String message) {
        return new ToolResult(false, TOOL_NAME, request.operation(), request.requestId(), request.conversationId(),
                false, List.of(), message, Map.of("error", message), code, message, false, "");
    }

    private ToolResult approvalRequired(ToolRequest request, CodingOperation operation, CodingService.CodingApproval approval) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("approvalId", approval.id());
        data.put("taskId", approval.taskId());
        data.put("operation", approval.operation());
        data.put("riskLevel", approval.riskLevel());
        data.put("argumentsDigest", approval.argumentsDigest());
        return new ToolResult(false, TOOL_NAME, operation.name(), request.requestId(), request.conversationId(),
                false, List.of("coding-task:" + approval.taskId()),
                "Approval required for Coding " + operation.name(), data, "CODING_APPROVAL_REQUIRED",
                "Approval required before executing " + operation.name(), true, approval.id());
    }

    private ApprovalDecision approvalDecision(CodingOperation operation, ToolRequest request) {
        ToolOperationRole role = ToolOperationClassifier.classify(TOOL_NAME, operation.name());
        boolean dangerous = operation == CodingOperation.FILE_DELETE || role == ToolOperationRole.WRITE && dangerousArguments(request.arguments());
        if (!dangerous) {
            return ApprovalDecision.notRequired();
        }
        String taskId = string(request.arguments().get("_codingTaskId"));
        if (taskId.isBlank()) {
            return ApprovalDecision.notRequired();
        }
        return new ApprovalDecision(true, taskId, "HIGH",
                "Coding " + operation.name() + " requires explicit approval.",
                digest(operation, request.arguments()));
    }

    private boolean dangerousArguments(Map<String, Object> arguments) {
        String command = string(arguments.get("command")).toLowerCase(Locale.ROOT);
        if (command.isBlank()) {
            return false;
        }
        List<String> tokens = List.of(command.split("[^a-z0-9_.-]+"));
        return containsCommand(tokens, "git", "commit")
                || containsCommand(tokens, "git", "push")
                || containsCommand(tokens, "git", "reset")
                || containsCommand(tokens, "git", "clean")
                || containsCommand(tokens, "git", "checkout")
                || containsCommand(tokens, "git", "rebase")
                || containsCommand(tokens, "git", "merge")
                || tokens.contains("rm")
                || tokens.contains("del")
                || tokens.contains("rmdir")
                || tokens.contains("remove-item");
    }

    private boolean containsCommand(List<String> tokens, String first, String second) {
        for (int i = 0; i < tokens.size() - 1; i++) {
            if (first.equals(tokens.get(i)) && second.equals(tokens.get(i + 1))) {
                return true;
            }
        }
        return false;
    }

    private String digest(CodingOperation operation, Map<String, Object> arguments) {
        TreeMap<String, String> canonical = new TreeMap<>();
        arguments.forEach((key, value) -> {
            if (!String.valueOf(key).startsWith("_")) {
                canonical.put(String.valueOf(key), String.valueOf(value));
            }
        });
        String payload = operation.name() + ":" + canonical;
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable.", exception);
        }
    }

    private CodingOperation operation(String operation) {
        try {
            return CodingOperation.valueOf(string(operation).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new ToolException("Unsupported coding operation: " + operation, exception);
        }
    }

    private boolean changed(CodingOperation operation) {
        return switch (operation) {
            case FILE_WRITE, FILE_PATCH, DIRECTORY_CREATE, FILE_MOVE, FILE_DELETE, COMMAND_START, COMMAND_CANCEL, BUILD_RUN, TEST_RUN -> true;
            default -> false;
        };
    }

    private ToolOperationDefinition operation(String name, String description, boolean write, ToolSafetyLevel safetyLevel, ToolArgumentDefinition... arguments) {
        return new ToolOperationDefinition(name, description, List.of(arguments), write, safetyLevel);
    }

    private ToolArgumentDefinition arg(String name, boolean required) {
        return new ToolArgumentDefinition(name, required, ToolJsonSchema.string(name));
    }

    private ToolArgumentDefinition intArg(String name, boolean required) {
        return new ToolArgumentDefinition(name, required, ToolJsonSchema.integer(name));
    }

    private ToolArgumentDefinition boolArg(String name, boolean required) {
        return new ToolArgumentDefinition(name, required, ToolJsonSchema.bool(name));
    }

    private String stringArg(ToolRequest request, String name) {
        return string(request.arguments().get(name));
    }

    private boolean boolArg(ToolRequest request, String name) {
        Object value = request.arguments().get(name);
        return value instanceof Boolean bool && bool;
    }

    private int intArg(ToolRequest request, String name) {
        Object value = request.arguments().get(name);
        return value instanceof Number number ? number.intValue() : 0;
    }

    private Integer nullableIntArg(ToolRequest request, String name) {
        Object value = request.arguments().get(name);
        return value instanceof Number number && number.intValue() > 0 ? number.intValue() : null;
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private enum CodingOperation {
        WORKSPACE_INSPECT,
        FILE_LIST,
        FILE_SEARCH,
        FILE_READ,
        FILE_WRITE,
        FILE_PATCH,
        DIRECTORY_CREATE,
        FILE_MOVE,
        FILE_DELETE,
        GIT_STATUS,
        GIT_DIFF,
        BUILD_DETECT,
        COMMAND_START,
        COMMAND_POLL,
        COMMAND_CANCEL,
        BUILD_RUN,
        TEST_RUN,
        BROWSER_LIST_TABS,
        BROWSER_EVALUATE,
        BROWSER_CONSOLE_LOGS,
        BROWSER_SCREENSHOT_DESCRIBE
    }

    private record ApprovalDecision(boolean required, String taskId, String riskLevel, String description, String argumentsDigest) {
        private static ApprovalDecision notRequired() {
            return new ApprovalDecision(false, "", "", "", "");
        }
    }
}
