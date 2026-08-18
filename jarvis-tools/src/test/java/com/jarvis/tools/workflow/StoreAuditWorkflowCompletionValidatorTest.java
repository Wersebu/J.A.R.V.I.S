package com.jarvis.tools.workflow;

import com.jarvis.common.ai.BrainType;
import com.jarvis.common.event.CognitiveEvent;
import com.jarvis.common.event.CognitiveEventBus;
import com.jarvis.common.event.CognitiveEventType;
import com.jarvis.tools.dataset.CandidateRecord;
import com.jarvis.tools.dataset.GeolocationEntry;
import com.jarvis.tools.dataset.GeolocationStatus;
import com.jarvis.tools.dataset.ScheduleDay;
import com.jarvis.tools.dataset.StoreAuditDataset;
import com.jarvis.tools.dataset.StoreAuditDatasetService;
import com.jarvis.tools.dataset.StoreRecord;
import com.jarvis.tools.dataset.VerificationEntry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for {@link StoreAuditWorkflowCompletionValidator}, covering both the round-2
 * "failed dataset creation is not the same as workflow complete" fix and the round-1 per-stage
 * gating this validator already enforced.
 */
class StoreAuditWorkflowCompletionValidatorTest {

    // TEST 4: a failed START_DATASET/CREATE_DATASET attempt (datasetCreationFailed=true) with a
    // blank activeDatasetId must NOT be treated as "workflow not engaged" - the old
    // `!datasetTouchedThisLoop() || activeDatasetId().isBlank() -> ok()` shortcut used to trigger
    // here since a failed creation call never assigns a datasetId.
    @Test
    void failedDatasetCreationIsNeverTreatedAsWorkflowComplete() {
        StoreAuditWorkflowCompletionValidator validator = new StoreAuditWorkflowCompletionValidator(service());
        WorkflowCompletionContext context = new WorkflowCompletionContext(
                "request-1", "conversation-1", true, "", true, true, "unknown declared attachment ids=[bad-id]");

        CompletionAssessment assessment = validator.assess(context);

        assertThat(assessment.complete()).isFalse();
        assertThat(assessment.reason()).isEqualTo("STORE_AUDIT_DATASET_CREATION_FAILED");
        assertThat(assessment.guidance()).contains("unknown declared attachment ids=[bad-id]");
    }

    // TEST 5: Store Audit never engaged at all this loop - the validator must not block ordinary
    // conversation just because a dataset mechanism exists in the codebase.
    @Test
    void workflowNeverEngagedIsNeverBlocked() {
        StoreAuditWorkflowCompletionValidator validator = new StoreAuditWorkflowCompletionValidator(service());
        WorkflowCompletionContext context = new WorkflowCompletionContext(
                "request-1", "conversation-1", false, "", true);

        CompletionAssessment assessment = validator.assess(context);

        assertThat(assessment.complete()).isTrue();
    }

    // TEST 6: dataset stage=EXTRACTED (not yet verified) - not complete.
    @Test
    void extractedStageIsNotComplete() {
        StoreAuditDatasetService service = service();
        StoreAuditDataset dataset = service.createDataset("request-1", 1, 0, List.of("att-1"), candidates(3)).dataset();
        StoreAuditWorkflowCompletionValidator validator = new StoreAuditWorkflowCompletionValidator(service);
        WorkflowCompletionContext context = new WorkflowCompletionContext(
                "request-1", "conversation-1", true, dataset.datasetId(), true);

        CompletionAssessment assessment = validator.assess(context);

        assertThat(assessment.complete()).isFalse();
        assertThat(assessment.reason()).isEqualTo("STORE_AUDIT_DATASET_NOT_SCHEDULED");
    }

    // TEST 7: dataset stage=LOCKED but the required workflow document has not been read yet this
    // loop - not complete, and the guidance must name the document path.
    @Test
    void lockedStageWithRequiredDocumentNotLoadedIsNotComplete() {
        StoreAuditDatasetService service = service();
        StoreAuditDataset dataset = verifyAll(service, service.createDataset("request-1", 1, 0, List.of("att-1"), candidates(3)).dataset());
        assertThat(dataset.stage()).isEqualTo(com.jarvis.tools.dataset.DatasetStage.LOCKED);
        StoreAuditWorkflowCompletionValidator validator = new StoreAuditWorkflowCompletionValidator(service);
        WorkflowCompletionContext context = new WorkflowCompletionContext(
                "request-1", "conversation-1", true, dataset.datasetId(), false);

        CompletionAssessment assessment = validator.assess(context);

        assertThat(assessment.complete()).isFalse();
        assertThat(assessment.reason()).isEqualTo("STORE_AUDIT_WORKFLOW_DOCUMENT_NOT_LOADED");
        assertThat(assessment.guidance()).contains("StoreAuditScheduleWorkflow.md");
    }

    // TEST 8: dataset stage=GEOLOCATED - not complete, SUBMIT_SCHEDULE still required.
    @Test
    void geolocatedStageIsNotComplete() {
        StoreAuditDatasetService service = service();
        StoreAuditDataset locked = verifyAll(service, service.createDataset("request-1", 1, 0, List.of("att-1"), candidates(3)).dataset());
        List<GeolocationEntry> geolocations = locked.stores().stream()
                .map(record -> new GeolocationEntry(record.id(), GeolocationStatus.RESOLVED, 52.0, 21.0)).toList();
        StoreAuditDataset geolocated = service.updateGeolocation(locked.datasetId(), geolocations).dataset();
        assertThat(geolocated.stage()).isEqualTo(com.jarvis.tools.dataset.DatasetStage.GEOLOCATED);
        StoreAuditWorkflowCompletionValidator validator = new StoreAuditWorkflowCompletionValidator(service);
        WorkflowCompletionContext context = new WorkflowCompletionContext(
                "request-1", "conversation-1", true, geolocated.datasetId(), true);

        CompletionAssessment assessment = validator.assess(context);

        assertThat(assessment.complete()).isFalse();
        assertThat(assessment.reason()).isEqualTo("STORE_AUDIT_DATASET_NOT_SCHEDULED");
    }

    // TEST 9: dataset stage=SCHEDULED - complete.
    @Test
    void scheduledStageIsComplete() {
        StoreAuditDatasetService service = service();
        StoreAuditDataset locked = verifyAll(service, service.createDataset("request-1", 1, 0, List.of("att-1"), candidates(3)).dataset());
        List<GeolocationEntry> geolocations = locked.stores().stream()
                .map(record -> new GeolocationEntry(record.id(), GeolocationStatus.RESOLVED, 52.0, 21.0)).toList();
        StoreAuditDataset geolocated = service.updateGeolocation(locked.datasetId(), geolocations).dataset();
        List<String> ids = geolocated.stores().stream().map(StoreRecord::id).toList();
        StoreAuditDataset scheduled = service.submitSchedule(geolocated.datasetId(), List.of(new ScheduleDay(1, ids))).dataset();
        assertThat(scheduled.stage()).isEqualTo(com.jarvis.tools.dataset.DatasetStage.SCHEDULED);
        StoreAuditWorkflowCompletionValidator validator = new StoreAuditWorkflowCompletionValidator(service);
        WorkflowCompletionContext context = new WorkflowCompletionContext(
                "request-1", "conversation-1", true, scheduled.datasetId(), true);

        CompletionAssessment assessment = validator.assess(context);

        assertThat(assessment.complete()).isTrue();
    }

    // A successful, later dataset creation clears the earlier failure - a pre-existing dataset
    // (e.g. a duplicate-source rejection returning the existing dataset) must fall through to the
    // normal stage-based logic rather than the creation-failure shortcut.
    @Test
    void datasetCreationFailedIsIgnoredOnceARealActiveDatasetExists() {
        StoreAuditDatasetService service = service();
        StoreAuditDataset dataset = service.createDataset("request-1", 1, 0, List.of("att-1"), candidates(3)).dataset();
        StoreAuditWorkflowCompletionValidator validator = new StoreAuditWorkflowCompletionValidator(service);
        WorkflowCompletionContext context = new WorkflowCompletionContext(
                "request-1", "conversation-1", true, dataset.datasetId(), true, true, "stale error from an earlier attempt");

        CompletionAssessment assessment = validator.assess(context);

        assertThat(assessment.reason()).isNotEqualTo("STORE_AUDIT_DATASET_CREATION_FAILED");
        assertThat(assessment.complete()).isFalse();
        assertThat(assessment.reason()).isEqualTo("STORE_AUDIT_DATASET_NOT_SCHEDULED");
    }

    private StoreAuditDatasetService service() {
        return new StoreAuditDatasetService(new NoopCognitiveEventBus());
    }

    private StoreAuditDataset verifyAll(StoreAuditDatasetService service, StoreAuditDataset dataset) {
        List<VerificationEntry> entries = dataset.stores().stream()
                .map(record -> new VerificationEntry(record.id(), "VERIFIED", "", ""))
                .toList();
        return service.verifyDataset(dataset.datasetId(), entries).dataset();
    }

    private List<CandidateRecord> candidates(int count) {
        List<CandidateRecord> candidates = new java.util.ArrayList<>();
        for (int index = 1; index <= count; index++) {
            candidates.add(new CandidateRecord("Biedronka", "Miasto Testowe", "Ulica Testowa",
                    String.valueOf(index), "00-00" + (index % 10), "Ulica Testowa " + index + ", Miasto Testowe",
                    "att-1", index));
        }
        return candidates;
    }

    private static final class NoopCognitiveEventBus implements CognitiveEventBus {

        @Override
        public void startRequest(String requestId, String conversationId, Consumer<CognitiveEvent> sink) {
        }

        @Override
        public void finishRequest() {
        }

        @Override
        public void updateBrain(BrainType brain, String model) {
        }

        @Override
        public void publish(CognitiveEventType event, String status, String message, String nodeId, Map<String, Object> metadata) {
        }
    }
}
