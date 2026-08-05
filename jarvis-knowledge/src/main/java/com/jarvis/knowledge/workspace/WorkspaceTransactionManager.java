package com.jarvis.knowledge.workspace;

/**
 * Creates transactions for safe knowledge workspace changes.
 */
public interface WorkspaceTransactionManager {

    /**
     * Begins a workspace transaction.
     *
     * @param requestId source request identifier
     * @param conversationId source conversation identifier
     * @param tool tool name
     * @param reason reason for the transaction
     * @param reasoningSummary concise AI reasoning summary
     * @return active transaction
     */
    WorkspaceTransaction begin(
            String requestId,
            String conversationId,
            String tool,
            String reason,
            String reasoningSummary
    );
}
