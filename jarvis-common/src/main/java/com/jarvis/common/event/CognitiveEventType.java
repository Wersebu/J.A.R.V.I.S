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
    /** Memory was used as a final prompt source. */
    MEMORY_USED,
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
    /** A knowledge document was added. */
    DOCUMENT_ADDED,
    /** A knowledge document was updated. */
    DOCUMENT_UPDATED,
    /** A knowledge document was removed. */
    DOCUMENT_REMOVED,
    /** Knowledge document reading started. */
    DOCUMENT_READING_STARTED,
    /** Knowledge document reading finished. */
    DOCUMENT_READING_FINISHED,
    /** Knowledge retrieval finished. */
    KNOWLEDGE_SEARCH_FINISHED,
    /** Agentic research mode started. */
    RESEARCH_STARTED,
    /** Agentic research planning started. */
    RESEARCH_PLANNING_STARTED,
    /** Agentic research model is planning the next knowledge action. */
    RESEARCH_PLANNING,
    /** Agentic research action was selected. */
    RESEARCH_ACTION_SELECTED,
    /** Agentic research search action started. */
    RESEARCH_SEARCH_STARTED,
    /** Research found a knowledge candidate. */
    KNOWLEDGE_CANDIDATE_FOUND,
    /** Agentic research search action finished. */
    RESEARCH_SEARCH_FINISHED,
    /** Agentic research document selection happened. */
    RESEARCH_DOCUMENT_SELECTED,
    /** Research document read started. */
    DOCUMENT_READ_STARTED,
    /** Research document content was received. */
    DOCUMENT_CONTENT_RECEIVED,
    /** Research document read finished. */
    DOCUMENT_READ_FINISHED,
    /** Agentic research document read action happened. */
    RESEARCH_DOCUMENT_READ,
    /** Research find action started. */
    DOCUMENT_FIND_STARTED,
    /** Research find action matched content. */
    DOCUMENT_FIND_MATCH,
    /** Research find action finished. */
    DOCUMENT_FIND_FINISHED,
    /** Agentic research section read action happened. */
    RESEARCH_SECTION_READ,
    /** Research source was accepted as supporting evidence. */
    RESEARCH_SOURCE_ACCEPTED,
    /** Research source was rejected. */
    RESEARCH_SOURCE_REJECTED,
    /** Research has enough grounded information to answer. */
    RESEARCH_READY_TO_ANSWER,
    /** Agentic research mode finished. */
    RESEARCH_FINISHED,
    /** Agentic research failed. */
    RESEARCH_ERROR,
    /** Context building started. */
    CONTEXT_BUILD_STARTED,
    /** A source was added to context. */
    SOURCE_ADDED,
    /** A knowledge source was injected into the final prompt. */
    KNOWLEDGE_SOURCE_INJECTED,
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
    /** Grounding diagnostics for the final response. */
    RESPONSE_GROUNDING,
    /** Streaming started. */
    STREAMING_STARTED,
    /** A generated token is available. */
    TOKEN,
    /** Streaming finished. */
    STREAMING_FINISHED,
    /** Request finished. */
    REQUEST_FINISHED,
    /** Request failed. */
    ERROR,
    /** Graph node was added. */
    GRAPH_NODE_ADDED,
    /** Graph node was updated. */
    GRAPH_NODE_UPDATED,
    /** Graph node was removed. */
    GRAPH_NODE_REMOVED,
    /** Graph node was moved. */
    GRAPH_NODE_MOVED,
    /** Graph edge was added. */
    GRAPH_EDGE_ADDED,
    /** Graph edge was removed. */
    GRAPH_EDGE_REMOVED,
    /** Knowledge workspace folder was created. */
    FOLDER_CREATED,
    /** Knowledge workspace document was created. */
    DOCUMENT_CREATED,
    /** Knowledge workspace document was moved. */
    DOCUMENT_MOVED,
    /** Knowledge workspace node was deleted. */
    DOCUMENT_DELETED,
    /** Knowledge workspace node was renamed. */
    DOCUMENT_RENAMED,
    /** Knowledge workspace index was refreshed. */
    INDEX_REFRESH,
    /** Knowledge graph model should be refreshed. */
    GRAPH_REFRESH,
    /** Tool execution started. */
    TOOL_STARTED,
    /** Tool read a document. */
    DOCUMENT_READ,
    /** Tool search started. */
    SEARCH_STARTED,
    /** Tool search result was produced. */
    SEARCH_RESULT,
    /** Tool search finished. */
    SEARCH_FINISHED,
    /** Tool execution finished. */
    TOOL_FINISHED,
    /** Native tool loop started. */
    TOOL_LOOP_STARTED,
    /** Tool selection started. */
    TOOL_SELECTION_STARTED,
    /** Model proposed a tool call. */
    TOOL_CALL_PROPOSED,
    /** Tool call was validated. */
    TOOL_CALL_VALIDATED,
    /** Tool call requires user approval. */
    TOOL_APPROVAL_REQUIRED,
    /** Tool execution started. */
    TOOL_EXECUTION_STARTED,
    /** Tool execution finished. */
    TOOL_EXECUTION_FINISHED,
    /** Tool result was received by the runtime. */
    TOOL_RESULT_RECEIVED,
    /** Tool result verification started. */
    TOOL_VERIFICATION_STARTED,
    /** Tool result verification finished. */
    TOOL_VERIFICATION_FINISHED,
    /** Native tool loop finished. */
    TOOL_LOOP_FINISHED,
    /** Native tool loop failed. */
    TOOL_LOOP_ERROR,
    /** Knowledge draft was created and waits for approval. */
    KNOWLEDGE_DRAFT_CREATED,
    /** Knowledge extraction started. */
    KNOWLEDGE_EXTRACTION_STARTED,
    /** Knowledge extraction finished. */
    KNOWLEDGE_EXTRACTION_FINISHED,
    /** Knowledge tree inspection started. */
    KNOWLEDGE_TREE_INSPECTION_STARTED,
    /** Knowledge tree inspection finished. */
    KNOWLEDGE_TREE_INSPECTION_FINISHED,
    /** Knowledge destination was planned. */
    KNOWLEDGE_DESTINATION_PLANNED,
    /** Duplicate knowledge was found. */
    KNOWLEDGE_DUPLICATE_FOUND,
    /** Conflicting knowledge was found. */
    KNOWLEDGE_CONFLICT_FOUND,
    /** Knowledge draft was approved. */
    KNOWLEDGE_DRAFT_APPROVED,
    /** Knowledge draft was rejected. */
    KNOWLEDGE_DRAFT_REJECTED,
    /** Knowledge write started. */
    KNOWLEDGE_WRITE_STARTED,
    /** Knowledge write finished. */
    KNOWLEDGE_WRITE_FINISHED,
    /** Knowledge write was verified. */
    KNOWLEDGE_WRITE_VERIFIED,
    /** Tool loop resumed after approval. */
    TOOL_LOOP_RESUMED,
    /** Final answer generation started. */
    FINAL_ANSWER_GENERATION_STARTED,
    /** Final answer generation finished. */
    FINAL_ANSWER_GENERATION_FINISHED
}
