package com.jarvis.tools.mcp;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Provider-independent MCP client abstraction.
 */
public interface McpClient extends AutoCloseable {

    /**
     * Initializes the MCP connection.
     */
    void initialize();

    /**
     * Lists tools exposed by the MCP server.
     *
     * @return MCP tool descriptors
     */
    List<McpToolDescriptor> listTools();

    /**
     * Calls one MCP tool.
     *
     * @param toolName MCP-native tool name
     * @param arguments tool arguments
     * @param timeout call timeout
     * @return call result
     */
    McpCallResult callTool(String toolName, Map<String, Object> arguments, Duration timeout);

    /**
     * Returns connection state.
     *
     * @return state
     */
    McpConnectionState state();

    /**
     * Closes the MCP connection.
     */
    @Override
    void close();
}
