package com.jarvis.knowledge.workspace;

/**
 * Request-scoped audit context used while a native tool modifies knowledge.
 */
public final class KnowledgeWorkspaceAuditContext {

    private static final ThreadLocal<Audit> CURRENT = new ThreadLocal<>();

    private KnowledgeWorkspaceAuditContext() {
    }

    /**
     * Activates audit metadata for the current thread.
     */
    public static void start(String requestId, String conversationId, String tool, String reason, String reasoningSummary) {
        CURRENT.set(new Audit(requestId, conversationId, tool, reason, reasoningSummary));
    }

    /**
     * Returns the current audit metadata.
     *
     * @return current audit metadata
     */
    public static Audit current() {
        Audit audit = CURRENT.get();
        return audit == null ? new Audit("", "", "", "", "") : audit;
    }

    /**
     * Clears the current audit metadata.
     */
    public static void clear() {
        CURRENT.remove();
    }

    /**
     * Audit metadata stored with document history.
     */
    public record Audit(String requestId, String conversationId, String tool, String reason, String reasoningSummary) {
    }
}
