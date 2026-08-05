package com.jarvis.tools;

import java.util.Map;

/**
 * Structured native tool result.
 *
 * @param success whether execution succeeded
 * @param output concise model-readable output
 * @param metadata structured diagnostics and data
 */
public record ToolResult(
        boolean success,
        String tool,
        String operation,
        String requestId,
        String conversationId,
        boolean changed,
        java.util.List<String> targetNodeIds,
        String message,
        Map<String, Object> data,
        String errorCode,
        String errorMessage,
        boolean requiresApproval,
        String draftId
) {

    /**
     * Creates an immutable result.
     */
    public ToolResult {
        targetNodeIds = targetNodeIds == null ? java.util.List.of() : java.util.List.copyOf(targetNodeIds);
        data = data == null ? Map.of() : Map.copyOf(data);
        tool = tool == null ? "" : tool;
        operation = operation == null ? "" : operation;
        requestId = requestId == null ? "" : requestId;
        conversationId = conversationId == null ? "" : conversationId;
        message = message == null ? "" : message;
        errorCode = errorCode == null ? "" : errorCode;
        errorMessage = errorMessage == null ? "" : errorMessage;
        draftId = draftId == null ? "" : draftId;
    }

    /**
     * Backward-compatible compact constructor.
     *
     * @param success whether execution succeeded
     * @param output result message
     * @param metadata result metadata
     */
    public ToolResult(boolean success, String output, Map<String, Object> metadata) {
        this(success, "", "", "", "", false, java.util.List.of(), output, metadata, "", "", false, "");
    }

    /**
     * Backward-compatible output accessor.
     *
     * @return result message
     */
    public String output() {
        return message;
    }

    /**
     * Backward-compatible metadata accessor.
     *
     * @return result data
     */
    public Map<String, Object> metadata() {
        return data;
    }
}
