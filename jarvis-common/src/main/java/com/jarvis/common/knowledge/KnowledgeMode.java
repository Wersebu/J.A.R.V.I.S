package com.jarvis.common.knowledge;

/**
 * Strategy used for accessing local knowledge during a chat request.
 */
public enum KnowledgeMode {
    /**
     * Selects the strategy automatically from request complexity.
     */
    AUTO,
    /**
     * Uses the classic low-latency retrieval augmented generation path.
     */
    FAST,
    /**
     * Lets the model iteratively search and read knowledge through controlled tools.
     */
    RESEARCH
}
