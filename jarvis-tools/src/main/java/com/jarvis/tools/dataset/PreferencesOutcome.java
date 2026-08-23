package com.jarvis.tools.dataset;

/**
 * Result of {@link StoreAuditDatasetService#setPreferences}.
 *
 * @param success whether the preferences were accepted and applied
 * @param dataset updated dataset (unchanged from before the call when {@code success} is false)
 * @param message human-readable summary
 * @param errorCode machine-readable rejection reason, blank when {@code success}
 */
public record PreferencesOutcome(boolean success, StoreAuditDataset dataset, String message, String errorCode) {
}
