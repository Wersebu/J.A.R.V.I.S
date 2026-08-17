package com.jarvis.tools.dataset;

import java.util.List;

/**
 * Result of {@link StoreAuditDatasetService#createDataset}.
 *
 * @param success whether a dataset was created (false only on a structural failure, e.g. declared
 *         attachment ids that don't match the request's real attachments)
 * @param dataset the created dataset, or null on failure
 * @param acceptedCount number of candidates accepted into the dataset
 * @param duplicateCount number of candidates skipped as exact duplicates of an already-accepted one
 * @param rejected candidates rejected for missing/invalid provenance or a blank address, with reasons
 * @param message human-readable summary
 */
public record CreateOutcome(
        boolean success,
        StoreAuditDataset dataset,
        int acceptedCount,
        int duplicateCount,
        List<RejectedCandidate> rejected,
        String message
) {

    /**
     * Normalizes the rejected list.
     */
    public CreateOutcome {
        rejected = rejected == null ? List.of() : List.copyOf(rejected);
    }
}
