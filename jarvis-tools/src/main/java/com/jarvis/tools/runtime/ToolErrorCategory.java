package com.jarvis.tools.runtime;

/**
 * Coarse result classification used by the native loop before it lets the model interpret a failed
 * tool call as ordinary text.
 */
enum ToolErrorCategory {
    RECOVERABLE,
    REQUIRES_USER,
    TERMINAL,
    RETRYABLE_TRANSIENT
}
