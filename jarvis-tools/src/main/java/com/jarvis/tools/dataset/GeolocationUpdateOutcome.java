package com.jarvis.tools.dataset;

import java.util.List;

/**
 * Result of {@link StoreAuditDatasetService#updateGeolocation}.
 *
 * @param success whether the dataset was found and updated
 * @param dataset updated dataset
 * @param updatedCount number of existing records updated
 * @param unknownRecordIds record ids in the update batch that don't exist in the dataset - these
 *         are reported, never used to create a new record
 * @param message human-readable summary
 */
public record GeolocationUpdateOutcome(
        boolean success,
        StoreAuditDataset dataset,
        int updatedCount,
        List<String> unknownRecordIds,
        String message
) {

    /**
     * Normalizes the unknown-record-id list.
     */
    public GeolocationUpdateOutcome {
        unknownRecordIds = unknownRecordIds == null ? List.of() : List.copyOf(unknownRecordIds);
    }
}
