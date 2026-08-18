package com.jarvis.tools.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Global MCP configuration.
 */
@ConfigurationProperties(prefix = "jarvis.mcp")
public class McpProperties {

    private boolean enabled;
    private Map<String, McpServerProperties> servers = new LinkedHashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Map<String, McpServerProperties> getServers() {
        return servers;
    }

    public void setServers(Map<String, McpServerProperties> servers) {
        this.servers = servers == null ? new LinkedHashMap<>() : new LinkedHashMap<>(servers);
    }
}
