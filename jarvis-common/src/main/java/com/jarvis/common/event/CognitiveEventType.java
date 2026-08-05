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
    /** Cognitive memory search started. */
    MEMORY_SEARCH_STARTED,
    /** A relevant memory was found. */
    MEMORY_FOUND,
    /** A scored memory candidate was found. */
    MEMORY_CANDIDATE_FOUND,
    /** Memory was injected into the prompt context. */
    MEMORY_INJECTED,
    /** No relevant memory was found. */
    MEMORY_NOT_FOUND,
    /** Prompt builder injected memory into the final prompt. */
    PROMPT_MEMORY_INJECTED,
    /** Background memory job was queued. */
    MEMORY_JOB_QUEUED,
    /** Background memory agent started. */
    MEMORY_AGENT_STARTED,
    /** Background memory agent made a decision. */
    MEMORY_AGENT_DECISION,
    /** A memory was deleted. */
    MEMORY_DELETED,
    /** Background memory agent finished. */
    MEMORY_AGENT_FINISHED,
    /** Background memory agent failed. */
    MEMORY_AGENT_ERROR,
    /** A memory was updated. */
    MEMORY_UPDATED,
    /** A memory was created. */
    MEMORY_CREATED,
    /** Memory update or retrieval was skipped. */
    MEMORY_SKIPPED,
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
    /** Native model thinking stream started. */
    THINKING_STARTED,
    /** Native model thinking fragment is available. */
    THINKING_TOKEN,
    /** Native model thinking stream finished. */
    THINKING_FINISHED,
    /** Final answer stream started. */
    ANSWER_STARTED,
    /** Final answer fragment is available. */
    ANSWER_TOKEN,
    /** Final answer stream finished. */
    ANSWER_FINISHED,
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
