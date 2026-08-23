package com.jarvis.tools.dataset;

/**
 * Result of {@link StoreAuditDatasetService#requestUserInput}.
 *
 * @param success whether the pause was recorded
 * @param dataset updated dataset (unchanged from before the call when {@code success} is false)
 * @param message human-readable summary
 * @param errorCode machine-readable rejection reason, blank when {@code success}
 */
public record PauseOutcome(boolean success, StoreAuditDataset dataset, String message, String errorCode) {
}
