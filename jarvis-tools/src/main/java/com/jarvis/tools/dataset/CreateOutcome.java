package com.jarvis.tools.dataset;

import java.util.List;

/**
 * Result of {@link StoreAuditDatasetService#createDataset}.
 *
 * @param success whether a dataset was created (false on a structural failure, e.g. declared
 *         attachment ids that don't match the request's real attachments, an empty/all-rejected
 *         record list, or a duplicate of an already-existing dataset for the same source)
 * @param dataset the created dataset on success; on a {@code STORE_DATASET_DUPLICATE_SOURCE}
 *         failure this is the existing dataset the caller should use instead; null for every other
 *         failure
 * @param acceptedCount number of candidates accepted into the dataset
 * @param duplicateCount number of candidates skipped as exact duplicates of an already-accepted one
 * @param rejected candidates rejected for missing/invalid provenance or a blank address, with reasons
 * @param message human-readable summary
 * @param errorCode machine-readable failure reason, blank on success - e.g. {@code EMPTY_DATASET},
 *         {@code STORE_DATASET_PROVENANCE_INVALID}, {@code STORE_DATASET_DUPLICATE_SOURCE}
 */
public record CreateOutcome(
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
    public CreateOutcome {
        rejected = rejected == null ? List.of() : List.copyOf(rejected);
        errorCode = errorCode == null ? "" : errorCode;
    }
}
