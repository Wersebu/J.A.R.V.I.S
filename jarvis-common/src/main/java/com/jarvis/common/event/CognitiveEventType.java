package com.jarvis.common.event;

/**
 * Unified cognitive event types emitted by the J.A.R.V.I.S. processing pipeline.
 */
public enum CognitiveEventType {
    /** Cognitive pipeline started. */
    PIPELINE_STARTED,
    /** Pipeline stage started. */
    STAGE_STARTED,
    /** Pipeline stage finished. */
    STAGE_FINISHED,
    /** Cognitive pipeline finished. */
    PIPELINE_FINISHED,
    /** Request entered the backend. */
    REQUEST_RECEIVED,
    /** Brain routing started. */
    BRAIN_ROUTING_STARTED,
    /** Task type was analyzed. */
    TASK_ANALYZED,
    /** Request complexity was analyzed. */
    COMPLEXITY_ANALYZED,
    /** Knowledge requirements were analyzed. */
    KNOWLEDGE_ANALYZED,
    /** Execution plan was created. */
    EXECUTION_PLAN_CREATED,
    /** Brain was selected. */
    BRAIN_SELECTED,
    /** Knowledge retrieval started. */
    KNOWLEDGE_SEARCH_STARTED,
    /** A matching knowledge document was found. */
    DOCUMENT_FOUND,
    /** Knowledge document reading started. */
    DOCUMENT_READING_STARTED,
    /** Knowledge document reading finished. */
    DOCUMENT_READING_FINISHED,
    /** Knowledge retrieval finished. */
    KNOWLEDGE_SEARCH_FINISHED,
    /** Context building started. */
    CONTEXT_BUILD_STARTED,
    /** A source was added to context. */
    SOURCE_ADDED,
    /** Context building finished. */
    CONTEXT_BUILD_FINISHED,
    /** Knowledge injection into the prompt started. */
    KNOWLEDGE_INJECTION_STARTED,
    /** Knowledge injection into the prompt finished. */
    KNOWLEDGE_INJECTION_FINISHED,
    /** Prompt building started. */
    PROMPT_BUILD_STARTED,
    /** Prompt building finished. */
    PROMPT_BUILD_FINISHED,
    /** Model request started. */
    MODEL_REQUEST_STARTED,
    /** Backend is waiting for the first model token. */
    WAITING_FIRST_TOKEN,
    /** First model token was received. */
    FIRST_TOKEN_RECEIVED,
    /** Streaming started. */
    STREAMING_STARTED,
    /** A generated token is available. */
    TOKEN,
    /** Streaming finished. */
    STREAMING_FINISHED,
    /** Request finished. */
    REQUEST_FINISHED,
    /** Request failed. */
    ERROR
}
