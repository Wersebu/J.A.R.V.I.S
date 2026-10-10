package com.jarvis.knowledge.vault.edit;

/**
 * Result of a user edit.
 *
 * @param path resulting path
 * @param version new version token (sha256 of the file), {@code ""} for folders
 * @param savedVersionId history version holding the previous content, or {@code ""}
 * @param message status message
 */
public record VaultWriteResult(String path, String version, String savedVersionId, String message) {
}
