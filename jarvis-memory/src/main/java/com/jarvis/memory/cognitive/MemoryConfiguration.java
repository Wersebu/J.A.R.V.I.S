package com.jarvis.memory.cognitive;

import com.jarvis.memory.conversation.ConversationHistoryProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Enables cognitive memory configuration properties.
 */
@Configuration
@EnableConfigurationProperties({MemoryProperties.class, ConversationHistoryProperties.class})
public class MemoryConfiguration {
}
