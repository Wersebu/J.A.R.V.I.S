package com.jarvis.memory.cognitive;

/**
 * Result type of a deterministic memory update attempt.
 */
public enum MemoryMutationType {
    /**
     * A new memory was created.
     */
    CREATED,

    /**
     * An existing memory was updated.
     */
    UPDATED,

    /**
     * The memory candidate already exists or was not useful.
     */
    SKIPPED
}
