package com.jarvis.tools.mcp;

import java.util.Locale;
import java.util.Map;

/**
 * Model-facing description of one tool discovered from an MCP server.
 *
 * @param serverId configured MCP server id
 * @param name MCP tool name
 * @param description MCP tool description
 * @param inputSchema JSON schema advertised by the MCP server
 * @param accessLevel server access level
 */
public record McpToolDescriptor(
        String serverId,
        String name,
        String description,
        Map<String, Object> inputSchema,
        McpAccessLevel accessLevel
) {

    /**
     * Creates a descriptor with immutable schema data.
     */
    public McpToolDescriptor {
        inputSchema = inputSchema == null ? Map.of() : Map.copyOf(inputSchema);
        accessLevel = accessLevel == null ? McpAccessLevel.READ : accessLevel;
    }

    /**
     * Returns the collision-safe Jarvis tool name exposed to the LLM.
     *
     * @return namespaced tool name
     */
    public String jarvisToolName() {
        return "mcp_" + sanitize(serverId) + "_" + sanitize(name);
    }

    private static String sanitize(String value) {
        String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return normalized.replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
    }
}
