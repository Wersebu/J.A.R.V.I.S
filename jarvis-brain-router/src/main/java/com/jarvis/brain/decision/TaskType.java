package com.jarvis.brain.decision;

/**
 * High-level category of a user request.
 */
public enum TaskType {
    /** Open-ended conversation. */
    CONVERSATION,
    /** Simple factual or direct question. */
    SIMPLE_QA,
    /** Question likely requiring local knowledge. */
    KNOWLEDGE_QA,
    /** Creative writing request. */
    CREATIVE_WRITING,
    /** Larger content generation request. */
    CONTENT_GENERATION,
    /** Programming or software engineering request. */
    PROGRAMMING,
    /** Code review request. */
    CODE_REVIEW,
    /** Analysis and reasoning request. */
    ANALYSIS,
    /** Summarization request. */
    SUMMARIZATION,
    /** Translation request. */
    TRANSLATION,
    /** Unknown or unsupported request type. */
    UNKNOWN
}
