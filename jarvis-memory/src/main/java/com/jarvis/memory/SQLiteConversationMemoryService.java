package com.jarvis.memory;

import com.jarvis.common.memory.ConversationMessage;
import com.jarvis.memory.cognitive.WorkingMemoryStore;
import com.jarvis.memory.conversation.ConversationMessageRepository;
import com.jarvis.memory.conversation.ConversationRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Conversation memory service backed by SQLite working memory, dual-writing every message into the
 * durable, never-trimmed conversation store alongside it.
 *
 * <p>{@link WorkingMemoryStore} intentionally keeps only the most recent {@code
 * jarvis.memory.working-history-length} messages per conversation - it is the fast, bounded window
 * prompt-building already relies on, and stays completely unchanged by this class. {@link
 * ConversationRepository}/{@link ConversationMessageRepository} are the separate, durable source of
 * truth: every message this service is asked to persist is also appended there, and nothing in this
 * class or its stores ever deletes a durable message as a side effect of trimming, restart, or
 * routine chat activity - only an explicit conversation delete does that (see {@link
 * #deleteConversation(String)}).</p>
 */
@Service
public class SQLiteConversationMemoryService implements ConversationMemoryService {

    private final WorkingMemoryStore workingMemoryStore;
    private final ConversationRepository conversationRepository;
    private final ConversationMessageRepository conversationMessageRepository;

    /**
     * Creates the SQLite conversation memory service.
     *
     * @param workingMemoryStore working memory store
     * @param conversationRepository durable conversation metadata store
     * @param conversationMessageRepository durable, never-trimmed conversation message store
     */
    public SQLiteConversationMemoryService(
            WorkingMemoryStore workingMemoryStore,
            ConversationRepository conversationRepository,
            ConversationMessageRepository conversationMessageRepository
    ) {
        this.workingMemoryStore = workingMemoryStore;
        this.conversationRepository = conversationRepository;
        this.conversationMessageRepository = conversationMessageRepository;
    }

    @Override
    public void addMessage(String conversationId, ConversationMessage message) {
        Instant now = Instant.now();
        workingMemoryStore.addMessage(conversationId, message);
        conversationRepository.createIfAbsent(conversationId, now);
        conversationMessageRepository.append(conversationId, message);
        conversationRepository.touch(conversationId, now);
    }

    @Override
    public List<ConversationMessage> getMessages(String conversationId) {
        return workingMemoryStore.getRecentMessages(conversationId);
    }

    @Override
    public int deleteConversation(String conversationId) {
        int deleted = workingMemoryStore.deleteConversation(conversationId);
        conversationMessageRepository.deleteAll(conversationId);
        conversationRepository.delete(conversationId);
        return deleted;
    }

    @Override
    public int countMessages(String conversationId) {
        return workingMemoryStore.countMessages(conversationId);
    }
}
