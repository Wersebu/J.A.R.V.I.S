package com.jarvis.tools.dataset;

/**
 * Result of {@link StoreAuditDatasetService#finalizeDataset}.
 *
 * @param success whether the dataset is now finalized (also true if it was already finalized -
 *         finalize is idempotent, since retrying it is a safe no-op rather than a real error)
 * @param dataset updated dataset (unchanged when {@code success} is false)
 * @param message human-readable summary
 * @param errorCode machine-readable failure reason, blank on success - e.g. {@code
 *         STORE_DATASET_NOT_FOUND}, {@code EMPTY_DATASET}
 */
public record FinalizeOutcome(
        boolean success,
        StoreAuditDataset dataset,
        String message,
        String errorCode
) {

    /**
     * Normalizes the error code.
     */
    public FinalizeOutcome {
        errorCode = errorCode == null ? "" : errorCode;
    }
}
