package com.jarvis.memory.cognitive;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Enables cognitive memory configuration properties.
 */
@Configuration
@EnableConfigurationProperties(MemoryProperties.class)
public class MemoryConfiguration {
}
