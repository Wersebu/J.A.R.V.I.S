package com.jarvis.memory.research;

/**
 * Supported tool actions for agentic research.
 */
public enum ResearchActionType {
    /** Search knowledge metadata. */
    SEARCH_KNOWLEDGE,
    /** List knowledge under a logical folder. */
    LIST_KNOWLEDGE,
    /** Read a full document or its first chunk. */
    READ_DOCUMENT,
    /** Find a phrase inside a document. */
    FIND_IN_DOCUMENT,
    /** Read a section or range from a document. */
    READ_SECTION,
    /** Produce the final answer. */
    FINAL_ANSWER
}
