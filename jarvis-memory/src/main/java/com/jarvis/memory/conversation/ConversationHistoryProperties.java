package com.jarvis.memory.conversation;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for short-term conversation context.
 */
@ConfigurationProperties(prefix = "jarvis.conversation.history")
public record ConversationHistoryProperties(
        boolean enabled,
        int maxMessages,
        int maxCharacters,
        boolean includeToolResults,
        boolean includeSystemEvents,
        boolean includeThinking
) {

    /**
     * Creates validated defaults.
     */
    public ConversationHistoryProperties {
        maxMessages = maxMessages <= 0 ? 30 : maxMessages;
        maxCharacters = maxCharacters <= 0 ? 30_000 : maxCharacters;
    }
}
