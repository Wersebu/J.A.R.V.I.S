package com.jarvis.tools.mcp;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Result of an MCP tool call.
 *
 * @param success whether the call completed successfully
 * @param content structured content items
 * @param structuredContent machine-readable MCP structured content
 * @param errorCode error code when the call failed
 * @param errorMessage error message when the call failed
 */
public record McpCallResult(
        boolean success,
        List<McpContentItem> content,
        Map<String, Object> structuredContent,
        String errorCode,
        String errorMessage
) {

    /**
     * Normalizes collection fields.
     */
    public McpCallResult {
        content = content == null ? List.of() : List.copyOf(content);
        structuredContent = structuredContent == null ? Map.of() : Map.copyOf(structuredContent);
    }

    /**
     * Joins textual content without discarding non-text structured fields.
     *
     * @return human-readable text content
     */
    public String text() {
        return content.stream()
                .map(McpContentItem::text)
                .filter(text -> text != null && !text.isBlank())
                .collect(Collectors.joining("\n"));
    }
}
