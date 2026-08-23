package com.jarvis.memory.conversation;

import java.time.Instant;

/**
 * Durable conversation metadata - separate from the message log itself ({@link
 * ConversationMessageRepository}), so switching/listing/renaming/archiving a conversation never
 * requires scanning its messages.
 *
 * @param id stable conversation identifier
 * @param title display title, defaults to {@code "Nowa rozmowa"} for a brand-new conversation until
 *         an auto-generated or user-set title replaces it
 * @param createdAt when this conversation was first created
 * @param updatedAt when this conversation last received a message
 * @param archived whether the conversation is archived (hidden from the default list, never deleted)
 * @param lastModel the most recently used model name for this conversation, blank if none yet
 * @param titleSource where the current title came from: DEFAULT, GENERATED, or USER
 * @param rollingSummary the current rolling summary text, blank if none has been generated yet
 * @param summaryUntilSequence the highest message {@code sequence_number} the rolling summary
 *         covers, {@code 0} when there is no summary yet
 */
public record ConversationRecord(
        String id,
        String userId,
        String folderId,
        String title,
        Instant createdAt,
        Instant updatedAt,
        boolean archived,
        String lastModel,
        String titleSource,
        String rollingSummary,
        long summaryUntilSequence
) {

    /**
     * Default title assigned to a brand-new conversation.
     */
    public static final String DEFAULT_TITLE = "Nowa rozmowa";

    public ConversationRecord(
            String id,
            String title,
            Instant createdAt,
            Instant updatedAt,
            boolean archived,
            String lastModel,
            String rollingSummary,
            long summaryUntilSequence
    ) {
        this(id, "local-user", "", title, createdAt, updatedAt, archived, lastModel, "DEFAULT", rollingSummary, summaryUntilSequence);
    }

    public ConversationRecord(
            String id,
            String title,
            Instant createdAt,
            Instant updatedAt,
            boolean archived,
            String lastModel,
            String titleSource,
            String rollingSummary,
            long summaryUntilSequence
    ) {
        this(id, "local-user", "", title, createdAt, updatedAt, archived, lastModel, titleSource, rollingSummary, summaryUntilSequence);
    }

    /**
     * Normalizes null fields.
     */
    public ConversationRecord {
        id = id == null ? "" : id;
        userId = userId == null || userId.isBlank() ? "local-user" : userId;
        folderId = folderId == null ? "" : folderId;
        title = title == null || title.isBlank() ? DEFAULT_TITLE : title;
        lastModel = lastModel == null ? "" : lastModel;
        titleSource = titleSource == null || titleSource.isBlank() ? "DEFAULT" : titleSource;
        rollingSummary = rollingSummary == null ? "" : rollingSummary;
    }
}
