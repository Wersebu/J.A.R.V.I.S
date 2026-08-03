package com.jarvis.brain;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Enables brain router configuration.
 */
@Configuration
@EnableConfigurationProperties(BrainCatalogProperties.class)
public class BrainRouterConfiguration {
}
