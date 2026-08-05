package com.jarvis.tools;

import java.util.Map;

/**
 * Structured native tool request.
 *
 * @param toolName requested tool name
 * @param operation tool-specific operation
 * @param conversationId source conversation identifier
 * @param requestId source request identifier
 * @param reason reason for invoking the tool
 * @param reasoningSummary short AI reasoning summary safe for diagnostics
 * @param arguments operation arguments
 */
public record ToolRequest(
        String toolName,
        String operation,
        String conversationId,
        String requestId,
        String reason,
        String reasoningSummary,
        Map<String, Object> arguments
) {

    /**
     * Creates an immutable request.
     */
    public ToolRequest {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }
}
