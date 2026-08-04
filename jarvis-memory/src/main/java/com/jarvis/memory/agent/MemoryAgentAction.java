package com.jarvis.memory.agent;

/**
 * Actions supported by the AI memory agent.
 */
public enum MemoryAgentAction {
    /** Do not change memory. */
    NONE,
    /** Create a memory. */
    CREATE,
    /** Update an existing memory. */
    UPDATE,
    /** Delete an existing memory. */
    DELETE
}
