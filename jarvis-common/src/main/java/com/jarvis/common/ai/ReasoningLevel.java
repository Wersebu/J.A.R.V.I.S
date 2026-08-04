package com.jarvis.common.ai;

/**
 * Native reasoning effort level supported by reasoning-capable models.
 */
public enum ReasoningLevel {
    /**
     * Low-latency reasoning for simple interactions.
     */
    LOW,

    /**
     * Balanced reasoning for knowledge and normal tasks.
     */
    MEDIUM,

    /**
     * Deep reasoning for programming, analysis, and large code tasks.
     */
    HIGH
}
