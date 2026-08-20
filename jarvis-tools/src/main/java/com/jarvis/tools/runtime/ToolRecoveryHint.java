package com.jarvis.tools.runtime;

/**
 * Structured recovery signal derived from a failed or write-like tool result.
 */
record ToolRecoveryHint(
        ToolErrorCategory category,
        ToolRecoveryReason reason,
        String requiredMode,
        String message
) {

    ToolRecoveryHint {
        category = category == null ? ToolErrorCategory.TERMINAL : category;
        reason = reason == null ? ToolRecoveryReason.NONE : reason;
        requiredMode = requiredMode == null ? "" : requiredMode;
        message = message == null ? "" : message;
    }

    static ToolRecoveryHint none() {
        return new ToolRecoveryHint(ToolErrorCategory.TERMINAL, ToolRecoveryReason.NONE, "", "");
    }

    boolean recoverable() {
        return category == ToolErrorCategory.RECOVERABLE || category == ToolErrorCategory.RETRYABLE_TRANSIENT;
    }
}
