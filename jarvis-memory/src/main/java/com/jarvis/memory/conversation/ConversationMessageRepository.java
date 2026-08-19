package com.jarvis.memory.conversation;

import com.jarvis.common.memory.ConversationMessage;

import java.util.List;

/**
 * Durable, never-trimmed conversation message log - distinct from {@code WorkingMemoryStore}, which
 * intentionally keeps only the most recent {@code jarvis.memory.working-history-length} messages
 * per conversation for fast prompt-window building. This store is the actual source of truth for
 * "what did this conversation ever contain" - nothing here is ever deleted except by an explicit
 * {@link #deleteAll(String)} call.
 */
public interface ConversationMessageRepository {

    /**
     * Appends a message - never trims or evicts older messages, unlike the working-memory store.
     *
     * @param conversationId stable conversation identifier
     * @param message message to store
     */
    void append(String conversationId, ConversationMessage message);

    /**
     * Returns every stored message for a conversation, in deterministic ascending order (sequence
     * number, then creation time as a tiebreaker).
     *
     * @param conversationId stable conversation identifier
     * @return full message history, oldest first
     */
    List<ConversationMessage> getAllMessages(String conversationId);

    /**
     * Counts stored messages for a conversation.
     *
     * @param conversationId stable conversation identifier
     * @return message count
     */
    int countMessages(String conversationId);

    /**
     * Permanently deletes every message for a conversation - only ever called as part of an
     * explicit, user-initiated conversation delete, never as routine trimming.
     *
     * @param conversationId stable conversation identifier
     * @return number of deleted rows
     */
    int deleteAll(String conversationId);
}
