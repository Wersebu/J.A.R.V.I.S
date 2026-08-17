package com.jarvis.tools.dataset;

/**
 * One model-submitted verification result for an existing {@link StoreRecord}, used by
 * {@link StoreAuditDatasetService#verifyDataset}. References an existing record by id - never
 * creates a new one.
 *
 * @param recordId id of the record being verified
 * @param status {@code "VERIFIED"} or {@code "CORRECTED"}
 * @param correctedFullAddress corrected address, or blank to keep the current value
 * @param correctedPostalCode corrected postal code, or blank to keep the current value
 */
public record VerificationEntry(
        String recordId,
        String status,
        String correctedFullAddress,
        String correctedPostalCode
) {
}
