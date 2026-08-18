package com.jarvis.tools.dataset;

import java.util.List;

/**
 * Result of {@link StoreAuditDatasetService#updateGeolocation}.
 *
 * @param success whether the dataset was found, in a stage that accepts geolocation, and updated
 * @param dataset updated dataset (unchanged from before the call when {@code success} is false)
 * @param updatedCount number of existing records updated
 * @param unknownRecordIds record ids in the update batch that don't exist in the dataset - these
 *         are reported, never used to create a new record
 * @param message human-readable summary
 * @param errorCode machine-readable failure reason, blank on success - e.g. {@code
 *         STORE_DATASET_NOT_FOUND}, {@code STORE_DATASET_NOT_VERIFIED}
 */
public record GeolocationUpdateOutcome(
        boolean success,
        StoreAuditDataset dataset,
        int updatedCount,
        List<String> unknownRecordIds,
        String message,
        String errorCode
) {

    /**
     * Normalizes the unknown-record-id list and error code.
     */
    public GeolocationUpdateOutcome {
        unknownRecordIds = unknownRecordIds == null ? List.of() : List.copyOf(unknownRecordIds);
        errorCode = errorCode == null ? "" : errorCode;
    }
}
