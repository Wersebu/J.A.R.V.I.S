package com.jarvis.tools.dataset;

import java.util.List;

/**
 * Result of {@link StoreAuditDatasetService#submitSchedule}: the count-invariant check that a
 * proposed day-by-day grouping references every canonical {@link StoreRecord} id in the locked
 * dataset exactly once - no store missing, none duplicated, none hallucinated.
 *
 * @param success whether the schedule passed validation and was applied
 * @param dataset updated dataset (unchanged from before the call when {@code success} is false)
 * @param invariantViolation true when the schedule was rejected for missing/unknown/duplicate ids
 * @param missingStoreIds dataset record ids that appear in no scheduled day at all
 * @param unknownStoreIds scheduled ids that don't exist in the dataset (hallucinated records)
 * @param duplicateStoreIds ids scheduled more than once across all days
 * @param message human-readable summary
 */
public record ScheduleSubmitOutcome(
        boolean success,
        StoreAuditDataset dataset,
        boolean invariantViolation,
        List<String> missingStoreIds,
        List<String> unknownStoreIds,
        List<String> duplicateStoreIds,
        String message
) {

    /**
     * Normalizes the id lists.
     */
    public ScheduleSubmitOutcome {
        missingStoreIds = missingStoreIds == null ? List.of() : List.copyOf(missingStoreIds);
        unknownStoreIds = unknownStoreIds == null ? List.of() : List.copyOf(unknownStoreIds);
        duplicateStoreIds = duplicateStoreIds == null ? List.of() : List.copyOf(duplicateStoreIds);
    }
}
