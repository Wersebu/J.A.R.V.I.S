package com.jarvis.tools.mcp;

import com.jarvis.tools.ToolRequest;

import java.util.List;
import java.util.Map;

/**
 * Manages configured MCP server lifecycle, discovery, and calls.
 */
public interface McpServerManager {

    /**
     * Connects a configured server.
     *
     * @param serverId server id
     */
    void connect(String serverId);

    /**
     * Disconnects a configured server.
     *
     * @param serverId server id
     */
    void disconnect(String serverId);

    /**
     * Returns all known statuses.
     *
     * @return status list
     */
    List<McpServerStatus> statuses();

    /**
     * Discovers enabled MCP tools.
     *
     * @return tool descriptors
     */
    List<McpToolDescriptor> discoverTools();

    /**
     * Activates every enabled MCP server that depends on a connected Windows bridge.
     */
    default void activateWindowsBridgeServers() {
    }

    /**
     * Clears runtime state for Windows-hosted MCP servers after the bridge disconnects.
     */
    default void handleWindowsBridgeDisconnected() {
    }

    /**
     * Calls an MCP tool.
     *
     * @param descriptor tool descriptor
     * @param request Jarvis tool request
     * @return MCP call result
     */
    McpCallResult call(McpToolDescriptor descriptor, ToolRequest request);
}
