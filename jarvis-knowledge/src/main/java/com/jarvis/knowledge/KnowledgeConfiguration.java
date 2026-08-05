package com.jarvis.knowledge;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Enables knowledge engine configuration.
 */
@Configuration
@EnableConfigurationProperties({KnowledgeProperties.class, com.jarvis.knowledge.workspace.KnowledgeWorkspaceProperties.class})
public class KnowledgeConfiguration {
}
