package com.jarvis.knowledge.vault;

/**
 * Selects how Jarvis retrieves knowledge.
 */
public enum VaultMode {
    /** Previous behaviour: document-level keyword + preview embedding retrieval. No chunk index is built. */
    LEGACY,
    /** Obsidian-compatible vault: full-content chunk index, hybrid chunk search and explicit workflow reads. */
    VAULT
}
