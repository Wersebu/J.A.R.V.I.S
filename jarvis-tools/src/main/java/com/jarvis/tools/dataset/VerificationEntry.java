package com.jarvis.tools.dataset;

/**
 * One model-submitted verification result for an existing {@link StoreRecord}, used by
 * {@link StoreAuditDatasetService#verifyDataset}. References an existing record by id - never
 * creates a new one.
 *
 * @param recordId id of the record being verified - resolved by {@link StoreDatasetTool} from the
 *         model-facing {@code recordIndex} before this reaches {@link StoreAuditDatasetService};
 *         still the field the internal domain logic (invariant checks, id lookups) operates on,
 *         unchanged, so a model calling this constructor directly (or an explicit typed-list/
 *         administrative caller) can still supply it directly
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
