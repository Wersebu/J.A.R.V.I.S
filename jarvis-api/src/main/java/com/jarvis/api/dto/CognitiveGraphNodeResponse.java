package com.jarvis.api.dto;

import java.util.Map;

/**
 * Hologram-safe cognitive graph node response.
 *
 * @param id stable node id
 * @param type node type
 * @param name display name
 * @param parentId parent node id
 * @param childCount direct child count
 * @param metadata visualization metadata
 */
public record CognitiveGraphNodeResponse(
        String id,
        String type,
        String name,
        String parentId,
        int childCount,
        Map<String, Object> metadata
) {
}
