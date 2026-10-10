package com.jarvis.knowledge.vault.edit;

import com.jarvis.knowledge.KnowledgeException;

import java.util.Map;

/**
 * Rejected vault edit with a machine-readable reason.
 */
public final class VaultEditException extends KnowledgeException {

    private final Reason reason;
    private final Map<String, Object> details;

    /**
     * Creates the exception.
     *
     * @param reason reason
     * @param message message
     * @param details extra data (for conflicts: current version and content)
     */
    public VaultEditException(Reason reason, String message, Map<String, Object> details) {
        super(message);
        this.reason = reason;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    /**
     * Returns the reason.
     *
     * @return reason
     */
    public Reason reason() {
        return reason;
    }

    /**
     * Returns extra data.
     *
     * @return details
     */
    public Map<String, Object> details() {
        return details;
    }

    /**
     * Rejection reason.
     */
    public enum Reason {
        /** Path or node does not exist. */
        NOT_FOUND,
        /** The file changed since the client read it (e.g. edited in Obsidian). */
        CONFLICT,
        /** Target already exists. */
        ALREADY_EXISTS,
        /** Malformed request or path. */
        INVALID,
        /** Path is excluded from the vault (internals, temp files, secrets, prompt). */
        EXCLUDED
    }
}
