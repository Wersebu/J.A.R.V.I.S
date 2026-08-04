package com.jarvis.brain;

import com.jarvis.brain.decision.DecisionProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Enables brain router configuration.
 */
@Configuration
@EnableConfigurationProperties({BrainCatalogProperties.class, DecisionProperties.class})
public class BrainRouterConfiguration {
}
