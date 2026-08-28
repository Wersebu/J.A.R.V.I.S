package com.jarvis.api.auth;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Enables TopkiMC moderation auth configuration binding.
 */
@Configuration
@EnableConfigurationProperties(TopkiMcModerationAuthProperties.class)
public class TopkiMcModerationAuthConfig {
}
