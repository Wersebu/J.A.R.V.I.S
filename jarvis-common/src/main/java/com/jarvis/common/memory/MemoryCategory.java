package com.jarvis.common.memory;

/**
 * Semantic category assigned to a memory by the memory agent.
 */
public enum MemoryCategory {
    /** Stable semantic fact. */
    SEMANTIC,
    /** User preference. */
    PREFERENCE,
    /** Project-related memory. */
    PROJECT,
    /** Person-related memory. */
    PERSON,
    /** Relationship memory. */
    RELATIONSHIP,
    /** Device or hardware memory. */
    DEVICE,
    /** Vehicle memory. */
    VEHICLE,
    /** Work-related memory. */
    WORK,
    /** Programming-related memory. */
    PROGRAMMING,
    /** Location-related memory. */
    LOCATION,
    /** Temporary memory. */
    TEMPORARY
}
