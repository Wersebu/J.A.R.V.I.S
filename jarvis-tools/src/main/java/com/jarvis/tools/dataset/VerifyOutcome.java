package com.jarvis.tools.dataset;

import java.util.List;

/**
 * Result of {@link StoreAuditDatasetService#verifyDataset}.
 *
 * @param success whether the verification pass was applied
 * @param dataset updated dataset (unchanged from before the call when {@code success} is false)
 * @param invariantViolation true when the verification pass was rejected because it referenced an
 *         unknown record id or reported a count wildly different from the locked dataset
 * @param unknownRecordIds record ids referenced by the verification pass that don't exist in the
 *         dataset - present only when {@code invariantViolation} is true
 * @param message human-readable summary
 */
public record VerifyOutcome(
        boolean success,
        StoreAuditDataset dataset,
        boolean invariantViolation,
        List<String> unknownRecordIds,
        String message
) {

    /**
     * Normalizes the unknown-record-id list.
     */
    public VerifyOutcome {
        unknownRecordIds = unknownRecordIds == null ? List.of() : List.copyOf(unknownRecordIds);
    }
}
