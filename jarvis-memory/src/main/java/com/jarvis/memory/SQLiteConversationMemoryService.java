package com.jarvis.memory;

import com.jarvis.common.memory.ConversationMessage;
import com.jarvis.memory.cognitive.WorkingMemoryStore;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Conversation memory service backed by SQLite working memory.
 */
@Service
public class SQLiteConversationMemoryService implements ConversationMemoryService {

    private final WorkingMemoryStore workingMemoryStore;

    /**
     * Creates the SQLite conversation memory service.
     *
     * @param workingMemoryStore working memory store
     */
    public SQLiteConversationMemoryService(WorkingMemoryStore workingMemoryStore) {
        this.workingMemoryStore = workingMemoryStore;
    }

    @Override
    public void addMessage(String conversationId, ConversationMessage message) {
        workingMemoryStore.addMessage(conversationId, message);
    }

    @Override
    public List<ConversationMessage> getMessages(String conversationId) {
        return workingMemoryStore.getRecentMessages(conversationId);
    }

    @Override
    public int deleteConversation(String conversationId) {
        return workingMemoryStore.deleteConversation(conversationId);
    }

    @Override
    public int countMessages(String conversationId) {
        return workingMemoryStore.countMessages(conversationId);
    }
}
