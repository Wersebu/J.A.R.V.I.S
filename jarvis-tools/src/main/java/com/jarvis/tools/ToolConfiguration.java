package com.jarvis.tools;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Enables native tool runtime configuration.
 */
@Configuration
@EnableConfigurationProperties({
        ToolRuntimeProperties.class,
        com.jarvis.tools.mcp.McpProperties.class,
        com.jarvis.tools.web.WebSearchProperties.class,
        com.jarvis.tools.location.LocationProperties.class
})
public class ToolConfiguration {
}
