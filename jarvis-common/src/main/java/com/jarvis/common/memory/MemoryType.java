package com.jarvis.common.memory;

/**
 * Types of cognitive memory supported by J.A.R.V.I.S.
 */
public enum MemoryType {
    /**
     * Short-term conversation memory.
     */
    WORKING,

    /**
     * Important remembered events.
     */
    EPISODIC,

    /**
     * Stable facts about the user or environment.
     */
    SEMANTIC,

    /**
     * Reusable instructions and workflows.
     */
    PROCEDURAL
}
