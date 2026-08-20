package com.jarvis.tools.runtime;

import com.jarvis.tools.ToolResult;
import com.jarvis.tools.workflow.ToolOperationClassifier;
import com.jarvis.tools.workflow.ToolOperationRole;

import java.util.Locale;
import java.util.Objects;

/**
 * Generic-ish classifier for failed tool/runtime states. It prefers structured error codes and
 * metadata, then falls back to bounded text heuristics for older MCP servers that only return text.
 */
final class ToolFailureClassifier {

    ToolRecoveryHint classify(ToolAction action, ToolResult result) {
        if (action == null || result == null || result.success()) {
            if (isWrite(action) && result != null && result.success()) {
                return new ToolRecoveryHint(ToolErrorCategory.RECOVERABLE,
                        ToolRecoveryReason.WRITE_VERIFICATION_REQUIRED, "", "Write accepted; read-back verification required.");
            }
            return ToolRecoveryHint.none();
        }
        String code = normalize(result.errorCode());
        String text = normalize(result.errorCode() + " " + result.errorMessage() + " " + result.message() + " " + result.data());
        if (containsAny(code, "session_not_connected", "stale_session", "not_connected")
                || containsAny(text, "requested studio_id is not connected", "studio id is not connected",
                "session not connected", "stale session", "temporarily disconnected")) {
            return new ToolRecoveryHint(ToolErrorCategory.RECOVERABLE, ToolRecoveryReason.STALE_SESSION, "",
                    "Connected runtime session is stale; rediscover and retry.");
        }
        String requiredMode = requiredMode(action, text);
        if (!requiredMode.isBlank() || containsAny(code, "wrong_runtime_mode", "wrong_datamodel_mode")) {
            return new ToolRecoveryHint(ToolErrorCategory.RECOVERABLE, ToolRecoveryReason.WRONG_RUNTIME_MODE,
                    requiredMode.isBlank() ? "Edit" : requiredMode,
                    "Runtime is in the wrong mode; transition mode and retry.");
        }
        if (containsAny(code, "target_not_found", "not_found", "stale_object_path")
                || containsAny(text, "target not found", "not found", "does not exist", "unknown path")) {
            return new ToolRecoveryHint(ToolErrorCategory.RECOVERABLE, ToolRecoveryReason.TARGET_NOT_FOUND, "",
                    "Target path was not found; search and retry when a candidate path is available.");
        }
        if (containsAny(code, "timeout", "temporarily_disconnected", "transient")
                || containsAny(text, "timeout", "temporarily unavailable", "try again")) {
            return new ToolRecoveryHint(ToolErrorCategory.RETRYABLE_TRANSIENT, ToolRecoveryReason.RETRYABLE_TRANSIENT, "",
                    "Transient tool failure; retry once.");
        }
        if (result.requiresApproval()) {
            return new ToolRecoveryHint(ToolErrorCategory.REQUIRES_USER, ToolRecoveryReason.NONE, "", "Approval required.");
        }
        return new ToolRecoveryHint(ToolErrorCategory.TERMINAL, ToolRecoveryReason.NONE, "", "");
    }

    private boolean isWrite(ToolAction action) {
        if (action == null) {
            return false;
        }
        return ToolOperationClassifier.classify(action.tool(), action.operation()) == ToolOperationRole.WRITE;
    }

    private String requiredMode(ToolAction action, String text) {
        if (action != null) {
            Object requested = action.arguments().get("datamodel_type");
            if (requested != null && !String.valueOf(requested).isBlank()) {
                return String.valueOf(requested);
            }
            String tool = action.tool().toLowerCase(Locale.ROOT);
            if (tool.contains("multi_edit") || tool.contains("script_write") || tool.contains("create_script")) {
                return "Edit";
            }
        }
        if (containsAny(text, "requires edit", "datamodel_type = edit", "datamodel type edit")) {
            return "Edit";
        }
        if (containsAny(text, "requires client", "client unavailable", "datamodel_type = client")) {
            return "Client";
        }
        if (containsAny(text, "requires server", "server unavailable", "datamodel_type = server")) {
            return "Server";
        }
        if (containsAny(text, "wrong runtime mode", "wrong datamodel mode", "play mode")) {
            return "Edit";
        }
        return "";
    }

    private boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private String normalize(Object value) {
        return Objects.toString(value, "").toLowerCase(Locale.ROOT).replace('-', '_');
    }
}
