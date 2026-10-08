package com.jarvis.core.pc;

import com.jarvis.api.service.WindowsCodingBridgeGateway;
import com.jarvis.tools.JarvisTool;
import com.jarvis.tools.ToolRequest;
import com.jarvis.tools.ToolResult;
import com.jarvis.tools.schema.ToolArgumentDefinition;
import com.jarvis.tools.schema.ToolDefinition;
import com.jarvis.tools.schema.ToolJsonSchema;
import com.jarvis.tools.schema.ToolOperationDefinition;
import com.jarvis.tools.schema.ToolSafetyLevel;
import com.jarvis.tools.schema.ToolSchemaProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Model-facing access to the files and shell of the user's Windows PC through the Windows bridge -
 * the Claude-Code-style Read/Write/Edit/Glob/Grep/Bash toolset, but for any folder the user allowed
 * in the Windows client's {@code config/pc-access.yml}, not only a registered Coding Workspace.
 *
 * <p>All path validation (allowed roots, canonical paths, symlink/junction escapes, size limits,
 * destructive-command blocklist) is enforced on the Windows side, where the files actually live.</p>
 */
@Service
public class PcTool implements JarvisTool, ToolSchemaProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(PcTool.class);
    private static final String TOOL_NAME = "pc";
    private static final Duration FAST = Duration.ofSeconds(30);
    private static final Duration SEARCH = Duration.ofSeconds(60);
    private static final long DEFAULT_SHELL_SECONDS = 120;
    private static final long MAX_SHELL_SECONDS = 900;
    /** Risky commands wait for the user's click on the PC (the dialog auto-denies after 5 minutes). */
    private static final Duration APPROVAL_WINDOW = Duration.ofSeconds(330);

    private final WindowsCodingBridgeGateway gateway;
    private final boolean enabled;

    /**
     * Creates the tool.
     *
     * @param gateway Windows bridge
     * @param enabled whether the tool is offered to the model
     */
    public PcTool(WindowsCodingBridgeGateway gateway, @Value("${jarvis.pc.enabled:true}") boolean enabled) {
        this.gateway = gateway;
        this.enabled = enabled;
    }

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public String getDescription() {
        return "Files and shell on the user's Windows PC (only inside folders the user allowed). Use absolute Windows "
                + "paths such as C:\\Users\\Name\\Documents\\file.txt. Call pc__info first to see the allowed folders.";
    }

    @Override
    public ToolDefinition definition() {
        if (!enabled) {
            return new ToolDefinition(TOOL_NAME, getDescription(), List.of());
        }
        ToolJsonSchema editItem = ToolJsonSchema.object(new java.util.LinkedHashMap<>(Map.of(
                "expected", ToolJsonSchema.string("Exact current text (copy from pc__read without the line-number prefix)"),
                "replacement", ToolJsonSchema.string("New text"),
                "replaceAll", ToolJsonSchema.bool("Replace every occurrence instead of requiring a unique match"))),
                List.of("expected", "replacement"), "One exact-text replacement");
        return new ToolDefinition(TOOL_NAME, getDescription(), List.of(
                op("INFO", "Show allowed folders, user home, OS and shell. Call this first when unsure where files are.",
                        ToolSafetyLevel.READ, false),
                op("LIST", "List a directory on the PC (absolute path; blank = first allowed folder).",
                        ToolSafetyLevel.READ, false, arg("path", false, "Absolute directory path")),
                op("READ", "Read a text file with line numbers ('   12<TAB>text'), 2000 lines per call; use startLine/limit "
                                + "to page through big files. Returns sha256 for pc__edit. Never copy the number prefix into edits.",
                        ToolSafetyLevel.READ, false, arg("path", true, "Absolute file path"),
                        intArg("startLine", "First line to read (1-based)"), intArg("limit", "Max lines (default 2000)")),
                op("FIND", "Find files by glob, newest first. '*.pdf' matches names anywhere below path; "
                                + "'src/**/*.java' matches relative paths. Skips .git/node_modules/target/build folders.",
                        ToolSafetyLevel.READ, false, arg("pattern", true, "Glob, e.g. *.docx or **/*Test.java"),
                        arg("path", false, "Absolute folder to search in"), intArg("maxResults", "Default 200")),
                op("GREP", "Search file contents like ripgrep. pattern is a regex (literal=true for plain text). "
                                + "outputMode: content (file:line:text, default), files (only file names) or count.",
                        ToolSafetyLevel.READ, false, arg("pattern", true, "Regex or text"),
                        arg("path", false, "Absolute folder or file"), arg("glob", false, "File-name filter, e.g. *.java or *.{ts,tsx}"),
                        boolArg("ignoreCase", "Case-insensitive"), boolArg("literal", "Treat pattern as plain text"),
                        intArg("context", "Lines of context around each match (0-5)"),
                        arg("outputMode", false, "content | files | count"), intArg("maxResults", "Default 100")),
                op("WRITE", "Create or overwrite a whole text file. For changes to an existing file prefer pc__edit or pc__patch.",
                        ToolSafetyLevel.WRITE, true, arg("path", true, "Absolute file path"), arg("content", true, "Full file content")),
                op("EDIT", "Replace exact text in a file. expected must occur exactly once (add surrounding lines to make it "
                                + "unique) unless replaceAll=true. For several changes in one file pass edits=[{expected, replacement}] "
                                + "- applied all-or-nothing. Pass expectedSha256 from pc__read to refuse editing a changed file.",
                        ToolSafetyLevel.WRITE, true, arg("path", true, "Absolute file path"),
                        arg("expected", false, "Exact current text"), arg("replacement", false, "New text"),
                        boolArg("replaceAll", "Replace all occurrences"),
                        new ToolArgumentDefinition("edits", false, ToolJsonSchema.arrayOf(editItem, "Several edits, applied in order")),
                        arg("expectedSha256", false, "sha256 from pc__read")),
                op("PATCH", "Apply a unified diff (one or many files; '--- a/x' '+++ b/x' '@@ ... @@' hunks with exact context "
                                + "lines). Paths relative to path. All files or none are changed. '--- /dev/null' creates a file.",
                        ToolSafetyLevel.WRITE, true, arg("patch", true, "Unified diff text"),
                        arg("path", false, "Base directory for relative paths in the diff")),
                op("MKDIR", "Create a directory (and parents).", ToolSafetyLevel.WRITE, true,
                        arg("path", true, "Absolute directory path")),
                op("MOVE", "Move or rename a file/folder within the same allowed folder.", ToolSafetyLevel.WRITE, true,
                        arg("sourcePath", true, "Absolute source path"), arg("targetPath", true, "Absolute target path")),
                op("DELETE", "Delete a file/folder. The user is asked to confirm on the PC; if they decline, do not retry.",
                        ToolSafetyLevel.DELETE, true, arg("path", true, "Absolute path")),
                op("SHELL", "Run a command in this conversation's terminal session: the working directory and env persist "
                                + "between calls ('cd project' then 'mvn test' works). cmd.exe by default, shell=powershell for "
                                + "PowerShell. Waits up to timeoutSeconds (default 120); if still running it keeps running in the "
                                + "background and you get a processId for pc__shell_wait - nothing is lost. background=true for "
                                + "servers/watchers. Output = stdout+stderr, long logs keep their beginning and end. Risky commands "
                                + "(git push, del/rm, reset --hard, ...) show a confirmation dialog to the user on the PC; if declined, "
                                + "do not retry. git, gh (GitHub CLI), npm, mvn, python etc. work if installed on the PC.",
                        ToolSafetyLevel.WRITE, true, arg("command", true, "Command line"),
                        arg("cwd", false, "Absolute working directory (changes the session directory)"),
                        intArg("timeoutSeconds", "Max wait before backgrounding (1-900, default 120)"),
                        boolArg("background", "Start and return immediately"), arg("shell", false, "cmd (default) or powershell"),
                        boolArg("killOnTimeout", "Kill instead of backgrounding when the wait is over")),
                op("SHELL_WAIT", "Wait for a background command: returns when it finishes, when untilPattern (regex) appears in "
                                + "new output, or after waitSeconds. Returns only output produced since the last call.",
                        ToolSafetyLevel.READ, false, arg("processId", true, "processId from pc__shell"),
                        intArg("waitSeconds", "Max wait (0-600, default 30)"),
                        arg("untilPattern", false, "Regex that ends the wait early, e.g. 'BUILD (SUCCESS|FAILURE)' or 'listening on'")),
                op("SHELL_TAIL", "Show the last lines of a command's output (does not consume it).", ToolSafetyLevel.READ, false,
                        arg("processId", true, "processId from pc__shell"), intArg("lines", "Default 100")),
                op("SHELL_CANCEL", "Stop a running command (and its child processes).", ToolSafetyLevel.WRITE, true,
                        arg("processId", true, "processId from pc__shell")),
                op("SHELL_LIST", "List terminal sessions (with their directories) and recent commands.", ToolSafetyLevel.READ, false),
                op("CHANGES", "List files changed by pc__write/edit/patch/delete in this conversation (newest first).",
                        ToolSafetyLevel.READ, false),
                op("UNDO", "Undo the last file changes made in this conversation (restores previous content, removes created "
                                + "files). Changes made through shell commands are not tracked.", ToolSafetyLevel.WRITE, true,
                        intArg("steps", "How many changes to undo (default 1)"))
        ));
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        String operation = request.operation() == null ? "" : request.operation().toUpperCase(Locale.ROOT);
        if (!enabled) {
            return failure(request, operation, "PC_DISABLED", "PC access is disabled in Core (jarvis.pc.enabled=false).");
        }
        Map<String, Object> arguments = new LinkedHashMap<>();
        request.arguments().forEach((key, value) -> {
            if (!key.startsWith("_") && value != null) {
                arguments.put(key, value);
            }
        });
        String workingDirectory = String.valueOf(request.arguments().getOrDefault("_workingDirectory", "")).strip();
        if (!workingDirectory.isBlank()) {
            // Windows resolves relative paths and new terminal sessions against the conversation folder.
            arguments.put("baseDir", workingDirectory);
        }
        if ("PROJECT_INSTRUCTIONS".equals(operation)) {
            // Internal (not offered to the model): loaded once per turn into the loop's system prompt.
            if (workingDirectory.isBlank()) {
                return failure(request, operation, "NO_WORKING_DIRECTORY", "No working directory");
            }
            try {
                Map<String, Object> result = gateway.codingRequest("pc_project_instructions", arguments, Duration.ofSeconds(10));
                return new ToolResult(true, TOOL_NAME, operation, request.requestId(), request.conversationId(), false,
                        List.of(), "Project instructions", result == null ? Map.of() : result, "", "", false, "");
            } catch (RuntimeException exception) {
                return failure(request, operation, "PC_TOOL_FAILED", String.valueOf(exception.getMessage()));
            }
        }
        if (!arguments.containsKey("session")) {
            // One session per conversation: terminal cwd/env and the undo history of file changes.
            arguments.put("session", request.conversationId() == null ? "default" : request.conversationId());
        }
        Duration timeout = switch (operation) {
            case "FIND", "GREP", "PATCH" -> SEARCH;
            case "SHELL" -> shellTimeout(arguments);
            case "SHELL_WAIT" -> Duration.ofSeconds(clamp(arguments, "waitSeconds", 30, 0, 600) + 30);
            // The PC asks the user before deleting; leave time for the answer.
            case "DELETE" -> APPROVAL_WINDOW;
            default -> FAST;
        };
        try {
            LOGGER.info("[PC_TOOL] requestId={} operation={} path={}", request.requestId(), operation,
                    arguments.getOrDefault("path", arguments.getOrDefault("cwd", "")));
            Map<String, Object> result = gateway.codingRequest("pc_" + operation.toLowerCase(Locale.ROOT), arguments, timeout);
            boolean changed = switch (operation) {
                case "WRITE", "EDIT", "PATCH", "MKDIR", "MOVE", "DELETE", "SHELL", "UNDO" -> true;
                default -> false;
            };
            return new ToolResult(true, TOOL_NAME, operation, request.requestId(), request.conversationId(), changed,
                    List.of(), "PC " + operation + " finished", result == null ? Map.of() : result, "", "", false, "");
        } catch (RuntimeException exception) {
            String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            if (message.contains("not connected") || message.contains("no Windows Bridge")) {
                message = message + " - the Jarvis Windows app must be running on the PC for pc__* tools.";
            }
            return failure(request, operation, "PC_TOOL_FAILED", message);
        }
    }

    private static long clamp(Map<String, Object> arguments, String key, long fallback, long min, long max) {
        long value = fallback;
        Object raw = arguments.get(key);
        if (raw != null) {
            try {
                value = (long) Double.parseDouble(String.valueOf(raw).strip());
            } catch (NumberFormatException ignored) {
                value = fallback;
            }
        }
        value = Math.max(min, Math.min(value, max));
        arguments.put(key, value);
        return value;
    }

    private Duration shellTimeout(Map<String, Object> arguments) {
        if (Boolean.parseBoolean(String.valueOf(arguments.getOrDefault("background", "false")))) {
            return APPROVAL_WINDOW;
        }
        long seconds = DEFAULT_SHELL_SECONDS;
        Object raw = arguments.get("timeoutSeconds");
        if (raw != null) {
            try {
                seconds = Long.parseLong(String.valueOf(raw).strip());
            } catch (NumberFormatException ignored) {
                seconds = DEFAULT_SHELL_SECONDS;
            }
        }
        seconds = Math.max(1, Math.min(seconds, MAX_SHELL_SECONDS));
        arguments.put("timeoutSeconds", seconds);
        // Windows enforces the command timeout itself; Core waits longer so it gets the real result,
        // including time for the user to approve a risky command on the PC.
        return Duration.ofSeconds(seconds).plus(APPROVAL_WINDOW);
    }

    private ToolResult failure(ToolRequest request, String operation, String code, String message) {
        return new ToolResult(false, TOOL_NAME, operation, request.requestId(), request.conversationId(), false,
                List.of(), message, Map.of("error", message), code, message, false, "");
    }

    private static ToolOperationDefinition op(String name, String description, ToolSafetyLevel safety, boolean write,
                                              ToolArgumentDefinition... arguments) {
        return new ToolOperationDefinition(name, description, List.of(arguments), write, safety);
    }

    private static ToolArgumentDefinition arg(String name, boolean required, String description) {
        return new ToolArgumentDefinition(name, "string", required, description);
    }

    private static ToolArgumentDefinition intArg(String name, String description) {
        return new ToolArgumentDefinition(name, "integer", false, description);
    }

    private static ToolArgumentDefinition boolArg(String name, String description) {
        return new ToolArgumentDefinition(name, "boolean", false, description);
    }
}
