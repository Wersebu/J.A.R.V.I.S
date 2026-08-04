package com.jarvis.common.memory;

/**
 * Priority level assigned to a memory by the memory agent.
 */
public enum MemoryPriority {
    /** Critical long-term memory. */
    CRITICAL,
    /** High-value memory. */
    HIGH,
    /** Normal memory. */
    NORMAL,
    /** Low-value memory. */
    LOW,
    /** Temporary memory. */
    TEMPORARY
}
