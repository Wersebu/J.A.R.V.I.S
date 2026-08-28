package com.jarvis.core.moderation;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Enables TopkiMC moderation configuration.
 */
@Configuration
@EnableConfigurationProperties(TopkiMcModerationProperties.class)
public class TopkiMcModerationConfig {
}
