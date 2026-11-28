package com.jarvis.memory.conversation;

import com.jarvis.common.ai.Brain;

/**
 * Generates a short title for a conversation once the first exchange is complete.
 */
public interface ConversationTitleService {

    /**
     * Schedules a one-shot title generation attempt when the conversation is still default-titled.
     *
     * @param conversationId conversation identifier
     * @param brain model/provider used for the completed request
     */
    void maybeGenerateTitleAsync(String conversationId, Brain brain);
    /** Cheap metadata update for a simple conversation; must not start another model generation. */
    default void maybeSetTitleWithoutInference(String conversationId) {}
}
