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
    CONNECTED_SYSTEM_INSPECTION,
    LOCATION,
    /**
     * A Store Audit scheduling workflow (canonical storeDataset creation/verification/geolocation/
     * scheduling) has been explicitly recognized - from real workflow state (an existing dataset for
     * this conversation) or from unambiguous vocabulary in the main model's own goal/reason, never
     * from the raw user message alone. Drives a restricted native tool catalog (see {@link
     * NativeToolSchemaMapper#resolveScope}) so an unrelated MCP/marketplace/web tool catalog is never
     * sent alongside a Store Audit task.
     */
    STORE_AUDIT,
    ORGANIZE_KNOWLEDGE,
    DELETE_KNOWLEDGE,
    NO_TOOL
}
