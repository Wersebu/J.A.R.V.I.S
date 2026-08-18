package com.jarvis.tools.mcp;

import java.util.Map;

/**
 * Structured content item returned by an MCP tool.
 *
 * @param type content type, for example text, image, audio, or resource
 * @param text textual content when available
 * @param mimeType content MIME type when available
 * @param data base64 or resource payload when available
 * @param annotations MCP annotations
 */
public record McpContentItem(
        String type,
        String text,
        String mimeType,
        String data,
        Map<String, Object> annotations
) {

    /**
     * Creates immutable content metadata.
     */
    public McpContentItem {
        annotations = annotations == null ? Map.of() : Map.copyOf(annotations);
    }
}
