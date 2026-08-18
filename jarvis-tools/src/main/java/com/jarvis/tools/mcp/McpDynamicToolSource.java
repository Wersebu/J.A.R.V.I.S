package com.jarvis.tools.mcp;

import com.jarvis.tools.DynamicToolSource;
import com.jarvis.tools.JarvisTool;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Dynamic Jarvis tool source backed by MCP discovery.
 */
@Service
public class McpDynamicToolSource implements DynamicToolSource {

    private final McpProperties properties;
    private final McpServerManager manager;
    private final McpSchemaMapper schemaMapper = new McpSchemaMapper();

    /**
     * Creates the MCP dynamic source.
     *
     * @param properties MCP properties
     * @param manager MCP server manager
     */
    public McpDynamicToolSource(McpProperties properties, McpServerManager manager) {
        this.properties = properties;
        this.manager = manager;
    }

    @Override
    public List<JarvisTool> tools() {
        if (!properties.isEnabled()) {
            return List.of();
        }
        return manager.discoverTools().stream()
                .map(descriptor -> (JarvisTool) new McpJarvisTool(descriptor, manager, schemaMapper))
                .toList();
    }
}
