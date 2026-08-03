package com.jarvis.common.ai;

/**
 * Logical AI brain categories supported by J.A.R.V.I.S.
 */
public enum BrainType {
    /**
     * Fast responses for simple requests.
     */
    FAST,

    /**
     * Deeper reasoning responses.
     */
    REASONING,

    /**
     * Code-oriented responses.
     */
    CODING,

    /**
     * Vision-capable responses.
     */
    VISION,

    /**
     * Planning-oriented responses.
     */
    PLANNING,

    /**
     * Memory-oriented responses.
     */
    MEMORY,

    /**
     * Embedding generation.
     */
    EMBEDDING,

    /**
     * Request classification.
     */
    CLASSIFIER
}
