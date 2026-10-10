package com.jarvis.knowledge.vault.index;

import java.util.List;
import java.util.Map;

/**
 * Snapshot of the chunk indexer for the status API and the Windows client.
 *
 * @param mode retrieval mode
 * @param state {@code DISABLED}, {@code STARTING}, {@code SCANNING}, {@code EMBEDDING}, {@code IDLE} or {@code ERROR}
 * @param documents indexed documents (all states)
 * @param searchableDocuments documents in state INDEXED
 * @param chunks fragments
 * @param embeddedChunks fragments with a vector in the current embedding space
 * @param pendingEmbeddings fragments still waiting for a vector
 * @param excludedDocuments documents excluded as secrets / prompt copies
 * @param errorDocuments documents with errors (path to error)
 * @param lastScanStarted ISO timestamp
 * @param lastScanFinished ISO timestamp
 * @param lastScanMs duration
 * @param lastScanChanges documents added/changed/moved/removed by the last scan
 * @param lastError last indexer error
 * @param embedding embedding provider status
 * @param indexDatabase SQLite file
 * @param vaultRoot vault root on the Core host
 */
public record VaultIndexStatus(
        String mode,
        String state,
        int documents,
        int searchableDocuments,
        int chunks,
        int embeddedChunks,
        int pendingEmbeddings,
        int excludedDocuments,
        List<Map<String, String>> errorDocuments,
        String lastScanStarted,
        String lastScanFinished,
        long lastScanMs,
        Map<String, Integer> lastScanChanges,
        String lastError,
        Map<String, Object> embedding,
        String indexDatabase,
        String vaultRoot
) {
}
