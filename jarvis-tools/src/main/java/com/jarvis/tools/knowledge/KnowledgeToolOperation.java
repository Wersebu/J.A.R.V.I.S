package com.jarvis.tools.knowledge;

/**
 * Operations supported by the native cognitive knowledge tool.
 */
public enum KnowledgeToolOperation {
    READ_DOCUMENT,
    CREATE_DOCUMENT,
    UPDATE_DOCUMENT,
    APPEND_DOCUMENT,
    DELETE_DOCUMENT,
    MOVE_DOCUMENT,
    RENAME_DOCUMENT,
    LIST_FOLDER,
    SEARCH_DOCUMENT,
    SEARCH_CONTENT,
    CREATE_FOLDER,
    DELETE_FOLDER,
    MOVE_FOLDER,
    LIST_TREE,
    DOCUMENT_EXISTS,
    PLAN_KNOWLEDGE_UPDATE,
    /** Vault mode: list workflow candidates (no procedure text). */
    FIND_WORKFLOW,
    /** Vault mode: read an explicitly selected workflow completely (in explicit parts when long). */
    READ_WORKFLOW
}
