package com.jarvis.tools.dataset;

import java.util.List;

/**
 * Result of {@link StoreAuditDatasetService#appendRecords}.
 *
 * @param success whether the batch was accepted and appended
 * @param dataset updated dataset (unchanged from before the call when {@code success} is false)
 * @param acceptedCount number of candidates in this batch accepted into the dataset
 * @param duplicateCount number of candidates in this batch skipped as duplicates of an
 *         already-accepted record (from this or an earlier batch)
 * @param rejected candidates rejected for missing/invalid provenance or a blank address
 * @param message human-readable summary
 * @param errorCode machine-readable failure reason, blank on success - e.g. {@code
 *         STORE_DATASET_NOT_FOUND}, {@code STORE_DATASET_NOT_BUILDING}, {@code EMPTY_APPEND}
 */
public record AppendOutcome(
        boolean success,
        StoreAuditDataset dataset,
        int acceptedCount,
        int duplicateCount,
        List<RejectedCandidate> rejected,
        String message,
        String errorCode
) {

    /**
     * Normalizes the rejected list and error code.
     */
    public AppendOutcome {
        rejected = rejected == null ? List.of() : List.copyOf(rejected);
        errorCode = errorCode == null ? "" : errorCode;
    }
}
