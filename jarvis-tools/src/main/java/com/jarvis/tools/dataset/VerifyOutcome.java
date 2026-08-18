package com.jarvis.tools.dataset;

import java.util.List;

/**
 * Result of {@link StoreAuditDatasetService#verifyDataset}: the full-coverage check that a
 * verification pass references every canonical {@link StoreRecord} id in the dataset exactly once
 * - no record missing, none duplicated, none hallucinated - mirroring the invariant {@link
 * StoreAuditDatasetService#submitSchedule} enforces on a proposed schedule.
 *
 * @param success whether the verification pass covered every record and was applied
 * @param dataset updated dataset (unchanged from before the call when {@code success} is false)
 * @param invariantViolation true when the verification pass was rejected for missing/duplicate/
 *         unknown record ids, or because the dataset was not in a state that accepts verification
 * @param missingRecordIds dataset record ids that appear in no verification entry at all -
 *         present only when {@code invariantViolation} is true
 * @param duplicateRecordIds record ids verified more than once in the same pass
 * @param unknownRecordIds record ids referenced by the verification pass that don't exist in the
 *         dataset
 * @param message human-readable summary
 */
public record VerifyOutcome(
        boolean success,
        StoreAuditDataset dataset,
        boolean invariantViolation,
        List<String> missingRecordIds,
        List<String> duplicateRecordIds,
        List<String> unknownRecordIds,
        String message
) {

    /**
     * Normalizes the record-id lists.
     */
    public VerifyOutcome {
        missingRecordIds = missingRecordIds == null ? List.of() : List.copyOf(missingRecordIds);
        duplicateRecordIds = duplicateRecordIds == null ? List.of() : List.copyOf(duplicateRecordIds);
        unknownRecordIds = unknownRecordIds == null ? List.of() : List.copyOf(unknownRecordIds);
    }
}
