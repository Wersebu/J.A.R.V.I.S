package com.jarvis.tools.runtime;

/**
 * Advisory tool intent detected from a user request.
 */
public enum ToolIntent {
    SAVE_KNOWLEDGE,
    CREATE_DOCUMENT,
    UPDATE_DOCUMENT,
    APPEND_DOCUMENT,
    READ_DOCUMENT,
    SEARCH_KNOWLEDGE,
    SEARCH_WEB,
    LOCATION,
    ORGANIZE_KNOWLEDGE,
    DELETE_KNOWLEDGE,
    NO_TOOL
}
