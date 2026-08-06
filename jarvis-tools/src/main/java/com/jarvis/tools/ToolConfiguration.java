package com.jarvis.tools;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Enables native tool runtime configuration.
 */
@Configuration
@EnableConfigurationProperties(ToolRuntimeProperties.class)
public class ToolConfiguration {
}
