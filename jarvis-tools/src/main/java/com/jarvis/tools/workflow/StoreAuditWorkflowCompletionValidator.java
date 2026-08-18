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

    /**
     * The authoritative Store Audit planning procedure, in the Knowledge Workspace - required
     * reading (via {@code knowledge__read_document}) before geolocation/scheduling proceeds, but
     * never duplicated into this class or any prompt: see {@link #requiredDocumentPath()}.
     */
    private static final String REQUIRED_WORKFLOW_DOCUMENT_PATH = "Work/Scheduling/StoreAuditScheduleWorkflow.md";

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
    public Optional<String> requiredDocumentPath() {
        return Optional.of(REQUIRED_WORKFLOW_DOCUMENT_PATH);
    }

    @Override
    public CompletionAssessment assess(WorkflowCompletionContext context) {
        if (context.datasetCreationFailed() && context.activeDatasetId().isBlank()) {
            // A rejected CREATE_DATASET/START_DATASET call sets datasetTouchedThisLoop=true but
            // never assigns an activeDatasetId (the operation never took a datasetId argument to
            // fall back on) - without this check that blank id would fall through to the generic
            // "workflow never engaged" shortcut below and let the loop present the failed attempt
            // as a finished task, instead of asking the model to fix and resubmit it.
            String reason = context.lastDatasetCreationErrorMessage().isBlank()
                    ? "no further detail was recorded."
                    : context.lastDatasetCreationErrorMessage();
            String guidance = "The Store Audit dataset creation call in this task FAILED and no dataset was "
                    + "created - the task is not finished. Reason: " + reason + " Fix the call based on that "
                    + "reason and resubmit storeDataset.CREATE_DATASET or storeDataset.START_DATASET - never "
                    + "tell the user the schedule/extraction is ready when dataset creation itself failed.";
            return new CompletionAssessment(false, "STORE_AUDIT_DATASET_CREATION_FAILED", guidance);
        }
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
        // The workflow document defines the actual planning rules (which days, how many stores per
        // day, ...) - once the dataset is verified and ready for geolocation/planning, this must be
        // read before proceeding, not skipped in favor of the model improvising its own grouping.
        boolean readyForWorkflowDocument = value.stage() == DatasetStage.LOCKED || value.stage() == DatasetStage.GEOLOCATED;
        if (readyForWorkflowDocument && !context.requiredDocumentLoaded()) {
            String guidance = "The Store Audit dataset (datasetId=" + value.datasetId() + ", stage=" + value.stage()
                    + ") is ready for geolocation/planning, but the authoritative workflow procedure has not been "
                    + "read yet this task. Call knowledge__read_document(path=\"" + REQUIRED_WORKFLOW_DOCUMENT_PATH
                    + "\") first, then follow its planning rules for grouping records into days - never invent "
                    + "grouping rules without reading it.";
            return new CompletionAssessment(false, "STORE_AUDIT_WORKFLOW_DOCUMENT_NOT_LOADED", guidance);
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
            case BUILDING -> "Call storeDataset.APPEND_RECORDS with the remaining records, then "
                    + "storeDataset.FINALIZE_DATASET once every record has been submitted.";
            case EXTRACTED -> "Call storeDataset.VERIFY_DATASET, covering every record exactly once, to lock "
                    + "verification (stage=LOCKED).";
            case LOCKED -> "Call location.GEOCODE_DATASET for the verified records, then storeDataset.SUBMIT_SCHEDULE.";
            case GEOLOCATED -> "Call storeDataset.SUBMIT_SCHEDULE with a day-by-day grouping covering every "
                    + "record exactly once.";
            case SCHEDULED -> "";
        };
    }

    /**
     * Single, deterministic source of truth for what operation must happen next in the Store Audit
     * state machine, given only a dataset's stage and whether the required workflow document has
     * been read this loop - used everywhere a "what's next" label is needed (the compact workflow
     * status block, completion-gate guidance, and hard stage-guard rejection messages) so there is
     * exactly one place this logic lives, never several drifting implementations of the same
     * pipeline.
     *
     * @param stage current dataset stage
     * @param requiredDocumentLoaded whether the required workflow document has been read this loop -
     *         irrelevant except at {@link DatasetStage#LOCKED}
     * @return the next required operation name (e.g. {@code "VERIFY_DATASET"}), {@code
     *         "READ_REQUIRED_WORKFLOW_DOCUMENT"} at {@code LOCKED} until the document is read, or
     *         blank at {@link DatasetStage#SCHEDULED} (nothing further required)
     */
    public static String nextRequiredAction(DatasetStage stage, boolean requiredDocumentLoaded) {
        return switch (stage) {
            case BUILDING -> "APPEND_RECORDS";
            case EXTRACTED -> "VERIFY_DATASET";
            case LOCKED -> requiredDocumentLoaded ? "GEOCODE_DATASET" : "READ_REQUIRED_WORKFLOW_DOCUMENT";
            case GEOLOCATED -> "SUBMIT_SCHEDULE";
            case SCHEDULED -> "";
        };
    }
}
