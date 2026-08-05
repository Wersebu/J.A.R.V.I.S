package com.jarvis.knowledge.workspace;

import java.time.Instant;
import java.util.Map;

/**
 * Result returned by a knowledge workspace tool operation.
 *
 * @param tool tool name
 * @param applied whether the filesystem was changed
 * @param draft whether this is a draft-only operation
 * @param message human readable status
 * @param nodeId affected logical node identifier
 * @param path affected relative path
 * @param timestamp result timestamp
 * @param data operation-specific data
 */
public record KnowledgeToolResult(
        String tool,
        boolean applied,
        boolean draft,
        String message,
        String nodeId,
        String path,
        Instant timestamp,
        Map<String, Object> data
) {

    /**
     * Creates a result with immutable data.
     */
    public KnowledgeToolResult {
        data = data == null ? Map.of() : Map.copyOf(data);
    }
}
