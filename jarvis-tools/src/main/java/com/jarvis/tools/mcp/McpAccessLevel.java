package com.jarvis.tools.mcp;

import com.jarvis.tools.schema.ToolSafetyLevel;

/**
 * Security level assigned to an MCP server.
 */
public enum McpAccessLevel {
    /** Read-only operations. */
    READ,
    /** Editing operations are allowed. */
    EDIT,
    /** Test or validation operations are allowed. */
    TEST,
    /** Autonomous operation is allowed by policy. */
    AUTONOMOUS;

    /**
     * Maps the MCP access policy onto the existing Jarvis tool safety model.
     *
     * @return Jarvis tool safety level
     */
    public ToolSafetyLevel toToolSafetyLevel() {
        return this == READ ? ToolSafetyLevel.READ : ToolSafetyLevel.WRITE;
    }
}
