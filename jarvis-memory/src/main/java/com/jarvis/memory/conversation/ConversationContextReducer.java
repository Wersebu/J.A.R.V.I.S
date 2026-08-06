package com.jarvis.memory.conversation;

import com.jarvis.common.memory.ConversationMessage;

import java.util.List;

/**
 * Reduces stored conversation history to the prompt-safe context window.
 */
public interface ConversationContextReducer {

    /**
     * Selects messages for the current request.
     *
     * @param messages stored messages in chronological order
     * @param currentRequestId current request identifier
     * @return selected context messages
     */
    List<ConversationMessage> reduce(List<ConversationMessage> messages, String currentRequestId);
}
