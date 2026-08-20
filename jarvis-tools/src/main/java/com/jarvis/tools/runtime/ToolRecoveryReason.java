package com.jarvis.tools.runtime;

/**
 * Specific recoverable blockers the core loop can handle without asking the user.
 */
enum ToolRecoveryReason {
    NONE,
    STALE_SESSION,
    WRONG_RUNTIME_MODE,
    TARGET_NOT_FOUND,
    WRITE_VERIFICATION_REQUIRED,
    RETRYABLE_TRANSIENT
}
