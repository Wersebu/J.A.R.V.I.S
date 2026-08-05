package com.jarvis.memory.research;

/**
 * Explicit state of an agentic knowledge research request.
 */
public enum ResearchState {
    /** Planning the next action. */
    PLANNING,
    /** Searching indexed knowledge metadata. */
    SEARCHING,
    /** Search results are available. */
    RESULTS_AVAILABLE,
    /** Selecting a candidate document. */
    SELECTING_DOCUMENT,
    /** Reading a document from the knowledge repository. */
    READING_DOCUMENT,
    /** Analyzing document content. */
    ANALYZING_DOCUMENT,
    /** More information is required. */
    NEEDS_MORE_INFORMATION,
    /** Enough grounded information exists to answer. */
    READY_TO_ANSWER,
    /** Research finished. */
    FINISHED,
    /** Research failed. */
    FAILED
}
