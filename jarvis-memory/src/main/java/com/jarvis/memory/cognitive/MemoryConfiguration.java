package com.jarvis.memory.cognitive;

import com.jarvis.memory.conversation.ConversationHistoryProperties;
import com.jarvis.memory.image.ConversationImageProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Enables cognitive memory configuration properties.
 */
@Configuration
@EnableConfigurationProperties({MemoryProperties.class, ConversationHistoryProperties.class, ConversationImageProperties.class})
public class MemoryConfiguration {
}
