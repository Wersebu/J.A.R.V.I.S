package com.jarvis.core.pc;

import com.jarvis.api.service.WindowsCodingBridgeGateway;
import com.jarvis.tools.JarvisTool;
import com.jarvis.tools.ToolRequest;
import com.jarvis.tools.ToolResult;
import com.jarvis.tools.schema.ToolArgumentDefinition;
import com.jarvis.tools.schema.ToolDefinition;
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
        return new ToolDefinition(TOOL_NAME, getDescription(), List.of(
                op("INFO", "Show allowed folders, user home, OS and shell. Call this first when unsure where files are.",
                        ToolSafetyLevel.READ, false),
                op("LIST", "List a directory on the PC (absolute path; blank = first allowed folder).",
                        ToolSafetyLevel.READ, false, arg("path", false, "Absolute directory path")),
                op("READ", "Read a text file on the PC, optionally a line range.", ToolSafetyLevel.READ, false,
                        arg("path", true, "Absolute file path"), intArg("startLine"), intArg("endLine")),
                op("FIND", "Find files by name below a folder. query is a glob like *.pdf or *raport*, or a regex with regex=true.",
                        ToolSafetyLevel.READ, false, arg("path", false, "Absolute folder to search in"),
                        arg("query", true, "File name glob, e.g. *.docx"), boolArg("regex"), intArg("maxResults")),
                op("GREP", "Search text inside files below a folder (literal, or regex with regex=true).",
                        ToolSafetyLevel.READ, false, arg("path", false, "Absolute folder to search in"),
                        arg("query", true, "Text or regex to find"), boolArg("regex"), intArg("maxResults")),
                op("WRITE", "Create or overwrite a text file on the PC with the full content.", ToolSafetyLevel.WRITE, true,
                        arg("path", true, "Absolute file path"), arg("content", true, "Full file content")),
                op("EDIT", "Replace an exact text fragment in a file (read the file first; expected must match exactly once).",
                        ToolSafetyLevel.WRITE, true, arg("path", true, "Absolute file path"),
                        arg("expected", true, "Exact current text"), arg("replacement", true, "New text")),
                op("MKDIR", "Create a directory (and parents).", ToolSafetyLevel.WRITE, true,
                        arg("path", true, "Absolute directory path")),
                op("MOVE", "Move or rename a file/folder within the same allowed folder.", ToolSafetyLevel.WRITE, true,
                        arg("sourcePath", true, "Absolute source path"), arg("targetPath", true, "Absolute target path")),
                op("DELETE", "Delete a file/folder. Only when the user explicitly asked for this deletion; set approved=true.",
                        ToolSafetyLevel.DELETE, true, arg("path", true, "Absolute path"), boolArg("approved")),
                op("SHELL", "Run a command on the PC (cmd.exe; use 'powershell -NoProfile -Command ...' for PowerShell) in cwd. "
                                + "Returns exit code, stdout and stderr. For long commands set async=true and poll with pc__shell_poll.",
                        ToolSafetyLevel.WRITE, true, arg("command", true, "Command line"),
                        arg("cwd", false, "Absolute working directory inside an allowed folder"),
                        intArg("timeoutSeconds"), boolArg("async"), intArg("maxOutputCharacters")),
                op("SHELL_POLL", "Get the status/output of an async command started with pc__shell.", ToolSafetyLevel.READ, false,
                        arg("processId", true, "processId returned by pc__shell")),
                op("SHELL_CANCEL", "Stop an async command.", ToolSafetyLevel.WRITE, true,
                        arg("processId", true, "processId returned by pc__shell"))
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
        Duration timeout = switch (operation) {
            case "FIND", "GREP" -> SEARCH;
            case "SHELL" -> shellTimeout(arguments);
            default -> FAST;
        };
        if ("DELETE".equals(operation) && !Boolean.parseBoolean(String.valueOf(arguments.getOrDefault("approved", "false")))) {
            return failure(request, operation, "PC_DELETE_NOT_APPROVED",
                    "Deletion needs the user's explicit request; ask the user, then call again with approved=true.");
        }
        try {
            LOGGER.info("[PC_TOOL] requestId={} operation={} path={}", request.requestId(), operation,
                    arguments.getOrDefault("path", arguments.getOrDefault("cwd", "")));
            Map<String, Object> result = gateway.codingRequest("pc_" + operation.toLowerCase(Locale.ROOT), arguments, timeout);
            boolean changed = switch (operation) {
                case "WRITE", "EDIT", "MKDIR", "MOVE", "DELETE", "SHELL" -> true;
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

    private Duration shellTimeout(Map<String, Object> arguments) {
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
        // Windows enforces the command timeout itself; Core waits a bit longer so it gets the real result.
        return Duration.ofSeconds(seconds + 30);
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

    private static ToolArgumentDefinition intArg(String name) {
        return new ToolArgumentDefinition(name, "integer", false, name);
    }

    private static ToolArgumentDefinition boolArg(String name) {
        return new ToolArgumentDefinition(name, "boolean", false, name);
    }
}
