package com.jarvis.memory.conversation;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Durable conversation metadata store - conversations, deliberately kept separate from the message
 * log ({@link ConversationMessageRepository}). Deleting a conversation here is always an explicit,
 * caller-initiated action; nothing in this codebase deletes a conversation as a side effect of
 * normal usage (context trimming, restart, or otherwise).
 */
public interface ConversationRepository {

    /**
     * Returns the existing conversation record, or creates one with default metadata (title {@link
     * ConversationRecord#DEFAULT_TITLE}) if none exists yet - idempotent, safe to call on every
     * message regardless of whether the conversation is already known.
     *
     * @param conversationId stable conversation identifier
     * @param now creation timestamp to use if a new record is created
     * @return the existing or newly created record
     */
    ConversationRecord createIfAbsent(String conversationId, Instant now);

    /**
     * Finds a conversation by id.
     *
     * @param conversationId stable conversation identifier
     * @return the record, if it exists
     */
    Optional<ConversationRecord> find(String conversationId);

    /**
     * Lists every conversation, most recently updated first.
     *
     * @return all conversation records, newest {@code updatedAt} first
     */
    List<ConversationRecord> list();

    /**
     * Updates a conversation's {@code updatedAt} timestamp - called whenever a message is
     * appended, so the conversation list can sort by real recent activity.
     *
     * @param conversationId stable conversation identifier
     * @param now new {@code updatedAt} value
     */
    void touch(String conversationId, Instant now);

    /**
     * Renames a conversation - a manually-set title, never subject to later auto-overwrite.
     *
     * @param conversationId stable conversation identifier
     * @param title new title
     */
    void rename(String conversationId, String title);

    /**
     * Archives or unarchives a conversation - never a destructive delete.
     *
     * @param conversationId stable conversation identifier
     * @param archived new archived state
     */
    void setArchived(String conversationId, boolean archived);

    /**
     * Records the most recently used model for a conversation, purely informational - switching
     * models must never change, delete, or otherwise affect the conversation itself.
     *
     * @param conversationId stable conversation identifier
     * @param model model name
     */
    void updateLastModel(String conversationId, String model);

    /**
     * Replaces the conversation's rolling summary.
     *
     * @param conversationId stable conversation identifier
     * @param summary new rolling summary text
     * @param coveredUntilSequence highest message sequence number the summary covers
     */
    void updateRollingSummary(String conversationId, String summary, long coveredUntilSequence);

    /**
     * Permanently deletes a conversation's metadata record - callers are responsible for also
     * deleting its messages ({@link ConversationMessageRepository#deleteAll(String)}) if a full,
     * explicit delete was actually requested.
     *
     * @param conversationId stable conversation identifier
     * @return {@code 1} if a record was deleted, {@code 0} if none existed
     */
    int delete(String conversationId);
}
