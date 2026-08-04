package com.jarvis.common.event;

import com.jarvis.common.context.KnowledgeUsage;

import java.time.Instant;

/**
 * Chat event emitted after generation when knowledge was injected.
 *
 * @param type event type
 * @param timestamp event timestamp
 * @param conversationId conversation identifier
 * @param usage knowledge usage metadata
 */
public record KnowledgeUsageEvent(
        ChatEventType type,
        Instant timestamp,
        String conversationId,
        KnowledgeUsage usage
) implements ChatEvent {

    /**
     * Creates a knowledge usage event.
     *
     * @param usage knowledge usage metadata
     * @return event
     */
    public static KnowledgeUsageEvent create(KnowledgeUsage usage) {
        return new KnowledgeUsageEvent(ChatEventType.KNOWLEDGE_USAGE, Instant.now(), usage.conversationId(), usage);
    }
}
