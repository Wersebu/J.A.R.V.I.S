package com.jarvis.knowledge.workspace;

import java.time.Instant;
import java.util.Map;

/**
 * Pending knowledge workspace draft waiting for approval.
 *
 * @param draftId draft identifier
 * @param tool tool name
 * @param path target logical path
 * @param summary short summary
 * @param createdAt creation time
 * @param data safe draft metadata
 */
public record KnowledgeDraft(
        String draftId,
        String tool,
        String path,
        String summary,
        Instant createdAt,
        Map<String, Object> data
) {

    /**
     * Creates an immutable draft.
     */
    public KnowledgeDraft {
        data = data == null ? Map.of() : Map.copyOf(data);
    }
}
