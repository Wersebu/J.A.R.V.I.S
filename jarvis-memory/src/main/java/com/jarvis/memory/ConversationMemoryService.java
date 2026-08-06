package com.jarvis.memory;

import com.jarvis.common.memory.ConversationMessage;

import java.util.List;

/**
 * Stores and retrieves conversation history.
 */
public interface ConversationMemoryService {

    /**
     * Adds a message to a conversation.
     *
     * @param conversationId stable conversation identifier
     * @param message message to store
     */
    void addMessage(String conversationId, ConversationMessage message);

    /**
     * Lists messages for a conversation.
     *
     * @param conversationId stable conversation identifier
     * @return immutable conversation history snapshot
     */
    List<ConversationMessage> getMessages(String conversationId);

    /**
     * Deletes all conversation history for a conversation.
     *
     * @param conversationId stable conversation identifier
     * @return deleted rows
     */
    default int deleteConversation(String conversationId) {
        return 0;
    }

    /**
     * Counts stored messages for a conversation.
     *
     * @param conversationId stable conversation identifier
     * @return message count
     */
    default int countMessages(String conversationId) {
        return getMessages(conversationId).size();
    }
}
