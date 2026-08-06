package com.jarvis.tools;

import com.jarvis.tools.knowledge.filing.KnowledgeFilingProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Enables native tool runtime configuration.
 */
@Configuration
@EnableConfigurationProperties({ToolRuntimeProperties.class, KnowledgeFilingProperties.class})
public class ToolConfiguration {
}
