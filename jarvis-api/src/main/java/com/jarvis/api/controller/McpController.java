package com.jarvis.api.controller;

import com.jarvis.tools.mcp.McpServerManager;
import com.jarvis.tools.mcp.McpServerStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Diagnostics API for Model Context Protocol server connections.
 */
@RestController
@RequestMapping("/api/v1/mcp")
public class McpController {

    private final McpServerManager mcpServerManager;

    /**
     * Creates the MCP controller.
     *
     * @param mcpServerManager MCP server manager
     */
    public McpController(McpServerManager mcpServerManager) {
        this.mcpServerManager = mcpServerManager;
    }

    /**
     * Returns configured MCP server statuses.
     *
     * @return MCP server statuses
     */
    @GetMapping("/status")
    public List<McpServerStatus> status() {
        return mcpServerManager.statuses();
    }

    /**
     * Connects a configured MCP server.
     *
     * @param serverId configured server id
     * @return updated MCP server statuses
     */
    @PostMapping("/{serverId}/connect")
    public List<McpServerStatus> connect(@PathVariable String serverId) {
        mcpServerManager.connect(serverId);
        return mcpServerManager.statuses();
    }

    /**
     * Disconnects a configured MCP server.
     *
     * @param serverId configured server id
     * @return updated MCP server statuses
     */
    @PostMapping("/{serverId}/disconnect")
    public List<McpServerStatus> disconnect(@PathVariable String serverId) {
        mcpServerManager.disconnect(serverId);
        return mcpServerManager.statuses();
    }
}
