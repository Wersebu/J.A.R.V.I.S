package com.jarvis.api.dto;

import java.util.Map;

/**
 * Generic request for invoking a knowledge workspace tool.
 *
 * @param tool tool name such as knowledge.createDocument
 * @param args tool arguments
 */
public record KnowledgeToolRequest(String tool, Map<String, Object> args) {

    /**
     * Creates a request with immutable argument map.
     */
    public KnowledgeToolRequest {
        args = args == null ? Map.of() : Map.copyOf(args);
    }
}
