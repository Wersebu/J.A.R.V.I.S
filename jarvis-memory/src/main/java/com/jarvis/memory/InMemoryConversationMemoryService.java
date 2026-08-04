package com.jarvis.memory;

import com.jarvis.common.memory.ConversationMessage;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory conversation history store for version 0.1.
 */
public class InMemoryConversationMemoryService implements ConversationMemoryService {

    private final Map<String, List<ConversationMessage>> conversations = new ConcurrentHashMap<>();

    /**
     * Adds a message to an in-memory conversation.
     *
     * @param conversationId stable conversation identifier
     * @param message message to store
     */
    @Override
    public void addMessage(String conversationId, ConversationMessage message) {
        conversations.computeIfAbsent(conversationId, ignored -> new CopyOnWriteArrayList<>()).add(message);
    }

    /**
     * Lists stored messages for a conversation.
     *
     * @param conversationId stable conversation identifier
     * @return immutable conversation history snapshot
     */
    @Override
    public List<ConversationMessage> getMessages(String conversationId) {
        return List.copyOf(conversations.getOrDefault(conversationId, List.of()));
    }
}
