package com.jarvis.tools.workflow;

import com.jarvis.tools.dataset.DatasetStage;
import com.jarvis.tools.dataset.StoreAuditDataset;
import com.jarvis.tools.dataset.StoreAuditDatasetService;

import java.util.Optional;

/**
 * Store Audit's concrete {@link WorkflowCompletionValidator}: once a dataset has actually been
 * worked on in this tool loop, the task is not complete until that dataset has reached {@link
 * DatasetStage#SCHEDULED} (a validated day-by-day schedule was accepted). This is what stops a
 * model from presenting extraction/geolocation progress as a finished "gotowy grafik" - the exact
 * failure mode this mechanism exists to prevent.
 */
public class StoreAuditWorkflowCompletionValidator implements WorkflowCompletionValidator {

    private final StoreAuditDatasetService datasetService;

    /**
     * Creates the validator.
     *
     * @param datasetService canonical Store Audit dataset store
     */
    public StoreAuditWorkflowCompletionValidator(StoreAuditDatasetService datasetService) {
        this.datasetService = datasetService;
    }

    @Override
    public CompletionAssessment assess(WorkflowCompletionContext context) {
        if (!context.datasetTouchedThisLoop() || context.activeDatasetId().isBlank()) {
            return CompletionAssessment.ok();
        }
        Optional<StoreAuditDataset> dataset = datasetService.getDataset(context.activeDatasetId());
        if (dataset.isEmpty()) {
            // Expired or otherwise unknown - nothing left to gate on.
            return CompletionAssessment.ok();
        }
        StoreAuditDataset value = dataset.get();
        if (value.stage() == DatasetStage.SCHEDULED) {
            return CompletionAssessment.ok();
        }
        String guidance = "The Store Audit dataset (datasetId=" + value.datasetId() + ") was used in this task but "
                + "is not finished yet: stage=" + value.stage() + ", " + value.stores().size() + " record(s). "
                + nextStepGuidance(value.stage())
                + " Do not tell the user the schedule is ready until storeDataset.SUBMIT_SCHEDULE has been "
                + "accepted covering every record. If you genuinely cannot proceed (e.g. an address is "
                + "ambiguous and needs the user's confirmation), say exactly that instead of presenting an "
                + "incomplete result as finished.";
        return new CompletionAssessment(false, "STORE_AUDIT_DATASET_NOT_SCHEDULED", guidance);
    }

    private String nextStepGuidance(DatasetStage stage) {
        return switch (stage) {
            case EXTRACTED -> "Call storeDataset.VERIFY_DATASET to lock verification, then "
                    + "location.GEOCODE_DATASET, then storeDataset.SUBMIT_SCHEDULE.";
            case LOCKED -> "Call location.GEOCODE_DATASET for the locked records, then storeDataset.SUBMIT_SCHEDULE.";
            case GEOLOCATED -> "Call storeDataset.SUBMIT_SCHEDULE with a day-by-day grouping covering every "
                    + "record exactly once.";
            case SCHEDULED -> "";
        };
    }
}
