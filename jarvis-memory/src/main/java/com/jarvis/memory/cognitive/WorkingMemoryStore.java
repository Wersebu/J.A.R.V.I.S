package com.jarvis.memory.cognitive;

import com.jarvis.common.memory.ConversationMessage;

import java.util.List;

/**
 * Stores short-term conversation memory.
 */
public interface WorkingMemoryStore {

    /**
     * Adds a conversation message.
     *
     * @param conversationId conversation identifier
     * @param message message to store
     */
    void addMessage(String conversationId, ConversationMessage message);

    /**
     * Returns recent messages for a conversation.
     *
     * @param conversationId conversation identifier
     * @return recent messages
     */
    List<ConversationMessage> getRecentMessages(String conversationId);
}
