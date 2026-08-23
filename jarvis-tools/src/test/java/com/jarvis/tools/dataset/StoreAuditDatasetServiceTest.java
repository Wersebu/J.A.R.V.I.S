package com.jarvis.tools.dataset;

import com.jarvis.common.ai.BrainType;
import com.jarvis.common.event.CognitiveEvent;
import com.jarvis.common.event.CognitiveEventBus;
import com.jarvis.common.event.CognitiveEventType;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests proving the Store Audit dataset invariants: the canonical record count is
 * locked at extraction, provenance is required, and neither verification nor geolocation can ever
 * add, remove, or silently change how many records exist - the exact failure mode ("~23 input
 * stores exploding to >100 internal stores across a tool loop") this mechanism exists to prevent.
 */
class StoreAuditDatasetServiceTest {

    // TEST A: 2 image attachments, extraction = 23 stores -> canonical dataset size = 23.
    @Test
    void createDatasetLocksTheExtractedRecordCountAsTheCanonicalDataset() {
        StoreAuditDatasetService service = service();
        List<CandidateRecord> candidates = candidates(23, "att-1", "att-2");

        CreateOutcome outcome = service.createDataset("request-1", 2, 0, List.of("att-1", "att-2"), candidates);

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.dataset().stores()).hasSize(23);
        assertThat(outcome.dataset().expectedStoreCount()).isEqualTo(23);
        assertThat(outcome.dataset().stage()).isEqualTo(DatasetStage.EXTRACTED);
    }

    // TEST B / provenance: a "record" with no valid attachment id (e.g. copied from workflow
    // documentation instead of read from the current message) is rejected, not added.
    @Test
    void recordsWithoutValidAttachmentProvenanceAreRejectedNotAdded() {
        StoreAuditDatasetService service = service();
        List<CandidateRecord> legit = new java.util.ArrayList<>(candidates(3, "att-1"));
        // A fourth "record" claiming to come from an attachment that was never declared - e.g. an
        // address copied from the workflow document's own example section.
        legit.add(new CandidateRecord("Biedronka", "Miasto Testowe", "Ulica Testowa", "99",
                "00-099", "Ulica Testowa 99, 00-099 Miasto Testowe", "", 99));

        CreateOutcome outcome = service.createDataset("request-1", 1, 0, List.of("att-1"), legit);

        assertThat(outcome.dataset().stores()).hasSize(3);
        assertThat(outcome.rejected()).hasSize(1);
        assertThat(outcome.rejected().get(0).reason()).contains("provenance");
    }

    // TEST G: model attempts to introduce a StoreRecord without source provenance -> rejected.
    @Test
    void createDatasetRejectsDeclaredAttachmentIdsNotKnownToCoreAsRealAttachments() {
        StoreAuditDatasetService service = service();
        service.registerAttachments("request-1", List.of("att-1", "att-2"));

        CreateOutcome outcome = service.createDataset("request-1", 1, 0, List.of("att-1", "att-fabricated"), candidates(3, "att-1"));

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.dataset()).isNull();
        assertThat(outcome.message()).contains("att-fabricated");
    }

    // Regression: CREATE_DATASET with an empty record list must never be accepted as a valid, if
    // empty, dataset - that silently set datasetAvailable=true downstream with nothing behind it.
    @Test
    void createDatasetRejectsAnEmptyRecordListAsEmptyDataset() {
        StoreAuditDatasetService service = service();

        CreateOutcome outcome = service.createDataset("request-1", 2, 0, List.of("att-1", "att-2"), List.of());

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.dataset()).isNull();
        assertThat(outcome.errorCode()).isEqualTo("EMPTY_DATASET");
    }

    // Regression: every candidate failing provenance also results in zero real records - same
    // failure mode as an empty submission, must be rejected the same way.
    @Test
    void createDatasetRejectsWhenEveryCandidateFailsProvenanceLeavingZeroRecords() {
        StoreAuditDatasetService service = service();
        List<CandidateRecord> allInvalid = List.of(
                new CandidateRecord("Biedronka", "Miasto", "Ulica", "1", "00-001",
                        "Ulica 1, 00-001 Miasto", "att-unknown", 1)
        );

        CreateOutcome outcome = service.createDataset("request-1", 1, 0, List.of("att-1"), allInvalid);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.dataset()).isNull();
        assertThat(outcome.errorCode()).isEqualTo("EMPTY_DATASET");
    }

    // Regression: a second CREATE_DATASET for the exact same conversation and source attachments
    // must not silently produce a second, independent dataset (the observed 23 -> 5 record drift).
    @Test
    void createDatasetRejectsADuplicateForTheSameConversationAndSourceAttachments() {
        StoreAuditDatasetService service = service();
        service.registerAttachments("request-1", "conversation-1", List.of("att-1", "att-2"));
        StoreAuditDataset first = service.createDataset("request-1", 2, 0, List.of("att-1", "att-2"), candidates(23, "att-1", "att-2")).dataset();

        service.registerAttachments("request-2", "conversation-1", List.of("att-1", "att-2"));
        CreateOutcome second = service.createDataset("request-2", 2, 0, List.of("att-1", "att-2"), candidates(5, "att-1", "att-2"));

        assertThat(second.success()).isFalse();
        assertThat(second.errorCode()).isEqualTo("STORE_DATASET_DUPLICATE_SOURCE");
        assertThat(second.dataset().datasetId()).isEqualTo(first.datasetId());
        assertThat(service.getDataset(first.datasetId()).orElseThrow().stores()).hasSize(23);
    }

    // A different set of source attachments in the same conversation is a genuinely new task (new
    // photos sent) and must still be allowed to create its own dataset.
    @Test
    void createDatasetAllowsADifferentDatasetForDifferentSourceAttachmentsInTheSameConversation() {
        StoreAuditDatasetService service = service();
        service.registerAttachments("request-1", "conversation-1", List.of("att-1"));
        StoreAuditDataset first = service.createDataset("request-1", 1, 0, List.of("att-1"), candidates(3, "att-1")).dataset();

        service.registerAttachments("request-2", "conversation-1", List.of("att-2"));
        CreateOutcome second = service.createDataset("request-2", 1, 0, List.of("att-2"), candidates(5, "att-2"));

        assertThat(second.success()).isTrue();
        assertThat(second.dataset().datasetId()).isNotEqualTo(first.datasetId());
    }

    // Regression for the exact reported bug: a single CREATE_DATASET call with a large record
    // array can fail (the model emits an empty records array despite intending to fill it) -
    // START_DATASET/APPEND_RECORDS/FINALIZE_DATASET let the same dataset be built incrementally.
    @Test
    void startAppendFinalizeBuildsTheSameCanonicalDatasetAsOneShotCreate() {
        StoreAuditDatasetService service = service();
        service.registerAttachments("request-1", "conversation-1", List.of("att-1"));

        CreateOutcome started = service.startDataset("request-1", 1, 0, List.of("att-1"), candidates(3, "att-1"));
        assertThat(started.success()).isTrue();
        assertThat(started.dataset().stage()).isEqualTo(DatasetStage.BUILDING);
        assertThat(started.dataset().stores()).hasSize(3);
        String datasetId = started.dataset().datasetId();

        List<CandidateRecord> nextBatch = new java.util.ArrayList<>();
        for (int index = 4; index <= 6; index++) {
            nextBatch.add(new CandidateRecord("Biedronka", "Miasto Testowe", "Ulica Testowa", String.valueOf(index),
                    "00-00" + (index % 10), "Ulica Testowa " + index + ", Miasto Testowe", "att-1", index));
        }
        AppendOutcome appended = service.appendRecords(datasetId, nextBatch);
        assertThat(appended.success()).isTrue();
        assertThat(appended.acceptedCount()).isEqualTo(3);
        assertThat(appended.dataset().stage()).isEqualTo(DatasetStage.BUILDING);
        assertThat(appended.dataset().stores()).hasSize(6);

        FinalizeOutcome finalized = service.finalizeDataset(datasetId);
        assertThat(finalized.success()).isTrue();
        assertThat(finalized.dataset().stage()).isEqualTo(DatasetStage.EXTRACTED);
        assertThat(finalized.dataset().stores()).hasSize(6);
        assertThat(finalized.dataset().expectedStoreCount()).isEqualTo(6);
        assertThat(service.getDataset(datasetId).orElseThrow().stores()).extracting(StoreRecord::id)
                .containsExactly("store-001", "store-002", "store-003", "store-004", "store-005", "store-006");
    }

    @Test
    void appendRecordsRejectsAnEmptyBatch() {
        StoreAuditDatasetService service = service();
        String datasetId = service.startDataset("request-1", 1, 0, List.of(), candidates(2, "att-1")).dataset().datasetId();

        AppendOutcome outcome = service.appendRecords(datasetId, List.of());

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.errorCode()).isEqualTo("EMPTY_APPEND");
        assertThat(service.getDataset(datasetId).orElseThrow().stores()).hasSize(2);
    }

    @Test
    void appendRecordsRejectsATargetThatIsAlreadyFinalized() {
        StoreAuditDatasetService service = service();
        StoreAuditDataset dataset = service.createDataset("request-1", 1, 0, List.of("att-1"), candidates(3, "att-1")).dataset();

        AppendOutcome outcome = service.appendRecords(dataset.datasetId(), candidates(1, "att-1"));

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.errorCode()).isEqualTo("STORE_DATASET_NOT_BUILDING");
        assertThat(service.getDataset(dataset.datasetId()).orElseThrow().stores()).hasSize(3);
    }

    // START_DATASET already refuses to create an empty BUILDING dataset (same EMPTY_DATASET
    // invariant as CREATE_DATASET), so a BUILDING dataset can never legitimately reach
    // FINALIZE_DATASET with 0 records through the public API - this proves that upstream guard.
    @Test
    void startDatasetAcceptsAnEmptyFirstBatchLeavingAnEmptyBuildingDataset() {
        // Unlike CREATE_DATASET (a one-shot call with no later stage to add records at),
        // START_DATASET is allowed to land empty - the model can still recover via
        // APPEND_RECORDS. Only FINALIZE_DATASET rejects a dataset that is still empty once the
        // model is done submitting batches (see finalizeDatasetRejectsAnEmptyDataset below).
        StoreAuditDatasetService service = service();

        CreateOutcome outcome = service.startDataset("request-1", 1, 0, List.of("att-1"), List.of());

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.dataset()).isNotNull();
        assertThat(outcome.dataset().stage()).isEqualTo(DatasetStage.BUILDING);
        assertThat(outcome.dataset().stores()).isEmpty();
    }

    @Test
    void finalizeDatasetRejectsAnEmptyDataset() {
        StoreAuditDatasetService service = service();
        String datasetId = service.startDataset("request-1", 1, 0, List.of("att-1"), List.of()).dataset().datasetId();

        FinalizeOutcome outcome = service.finalizeDataset(datasetId);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.errorCode()).isEqualTo("EMPTY_DATASET");
        assertThat(service.getDataset(datasetId).orElseThrow().stage()).isEqualTo(DatasetStage.BUILDING);
    }

    @Test
    void finalizeDatasetIsIdempotentWhenAlreadyFinalized() {
        StoreAuditDatasetService service = service();
        String datasetId = service.createDataset("request-1", 1, 0, List.of("att-1"), candidates(3, "att-1")).dataset().datasetId();

        FinalizeOutcome first = service.finalizeDataset(datasetId);
        FinalizeOutcome second = service.finalizeDataset(datasetId);

        assertThat(first.success()).isTrue();
        assertThat(second.success()).isTrue();
        assertThat(second.dataset().stage()).isEqualTo(DatasetStage.EXTRACTED);
        assertThat(second.dataset().stores()).hasSize(3);
    }

    @Test
    void verifyGeolocationAndScheduleAllRejectAStillBuildingDataset() {
        StoreAuditDatasetService service = service();
        String datasetId = service.startDataset("request-1", 1, 0, List.of("att-1"), candidates(3, "att-1")).dataset().datasetId();

        VerifyOutcome verify = service.verifyDataset(datasetId, List.of(new VerificationEntry("store-001", "VERIFIED", "", "")));
        GeolocationUpdateOutcome geo = service.updateGeolocation(datasetId,
                List.of(new GeolocationEntry("store-001", GeolocationStatus.RESOLVED, 52.0, 21.0)));
        ScheduleSubmitOutcome schedule = service.submitSchedule(datasetId,
                List.of(scheduleDay(1, LocalDate.of(2026, 6, 2), List.of("store-001"))));

        assertThat(verify.success()).isFalse();
        assertThat(verify.message()).contains("BUILDING");
        assertThat(geo.success()).isFalse();
        assertThat(geo.message()).contains("BUILDING");
        assertThat(schedule.success()).isFalse();
        assertThat(schedule.message()).contains("BUILDING");
        assertThat(service.getDataset(datasetId).orElseThrow().stage()).isEqualTo(DatasetStage.BUILDING);
    }

    @Test
    void startDatasetAppliesTheSameDuplicateSourceCheckAsCreateDataset() {
        StoreAuditDatasetService service = service();
        service.registerAttachments("request-1", "conversation-1", List.of("att-1"));
        StoreAuditDataset first = service.startDataset("request-1", 1, 0, List.of("att-1"), candidates(3, "att-1")).dataset();

        service.registerAttachments("request-2", "conversation-1", List.of("att-1"));
        CreateOutcome second = service.startDataset("request-2", 1, 0, List.of("att-1"), candidates(3, "att-1"));

        assertThat(second.success()).isFalse();
        assertThat(second.errorCode()).isEqualTo("STORE_DATASET_DUPLICATE_SOURCE");
        assertThat(second.dataset().datasetId()).isEqualTo(first.datasetId());
    }

    // TEST C: GeoLocation returns 23 results -> dataset remains 23 records.
    @Test
    void geolocationUpdatesResolveExistingRecordsWithoutChangingCount() {
        StoreAuditDatasetService service = service();
        StoreAuditDataset dataset = verifyAll(service, service.createDataset("request-1", 1, 0, List.of("att-1"), candidates(23, "att-1")).dataset());

        List<GeolocationEntry> results = dataset.stores().stream()
                .map(record -> new GeolocationEntry(record.id(), GeolocationStatus.RESOLVED, 52.0, 21.0))
                .toList();
        GeolocationUpdateOutcome outcome = service.updateGeolocation(dataset.datasetId(), results);

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.dataset().stores()).hasSize(23);
        assertThat(outcome.updatedCount()).isEqualTo(23);
        assertThat(outcome.dataset().stage()).isEqualTo(DatasetStage.GEOLOCATED);
        assertThat(outcome.dataset().stores()).allMatch(record -> record.geolocationStatus() == GeolocationStatus.RESOLVED);
    }

    // TEST D: one GeoLocation fails -> dataset remains 23, one record gets FAILED status.
    @Test
    void oneFailedGeolocationLeavesDatasetSizeUnchangedAndMarksOnlyThatRecordFailed() {
        StoreAuditDatasetService service = service();
        StoreAuditDataset dataset = verifyAll(service, service.createDataset("request-1", 1, 0, List.of("att-1"), candidates(23, "att-1")).dataset());

        List<GeolocationEntry> results = new java.util.ArrayList<>();
        for (int index = 0; index < dataset.stores().size(); index++) {
            StoreRecord record = dataset.stores().get(index);
            results.add(index == 5
                    ? new GeolocationEntry(record.id(), GeolocationStatus.FAILED, null, null)
                    : new GeolocationEntry(record.id(), GeolocationStatus.RESOLVED, 52.0, 21.0));
        }
        GeolocationUpdateOutcome outcome = service.updateGeolocation(dataset.datasetId(), results);

        assertThat(outcome.dataset().stores()).hasSize(23);
        long failed = outcome.dataset().stores().stream().filter(record -> record.geolocationStatus() == GeolocationStatus.FAILED).count();
        assertThat(failed).isEqualTo(1);
    }

    // TEST E: GeoLocation retry succeeds -> same record is updated, dataset remains 23.
    @Test
    void retryingAFailedGeolocationUpdatesTheSameRecordRatherThanCreatingANewOne() {
        StoreAuditDatasetService service = service();
        StoreAuditDataset dataset = verifyAll(service, service.createDataset("request-1", 1, 0, List.of("att-1"), candidates(23, "att-1")).dataset());
        String failingId = dataset.stores().get(5).id();

        service.updateGeolocation(dataset.datasetId(), List.of(new GeolocationEntry(failingId, GeolocationStatus.FAILED, null, null)));
        GeolocationUpdateOutcome retry = service.updateGeolocation(dataset.datasetId(),
                List.of(new GeolocationEntry(failingId, GeolocationStatus.RESOLVED, 52.1, 21.1)));

        assertThat(retry.dataset().stores()).hasSize(23);
        StoreRecord updated = retry.dataset().stores().stream().filter(record -> record.id().equals(failingId)).findFirst().orElseThrow();
        assertThat(updated.geolocationStatus()).isEqualTo(GeolocationStatus.RESOLVED);
        assertThat(updated.latitude()).isEqualTo(52.1);
    }

    // TEST F: duplicate model output for the same source row -> deduplicated deterministically,
    // no silent dataset growth.
    @Test
    void duplicateCandidatesForTheSameSourceRowAreDeduplicatedNotDuplicated() {
        StoreAuditDatasetService service = service();
        List<CandidateRecord> candidates = new java.util.ArrayList<>(candidates(3, "att-1"));
        // The model accidentally re-submits row 1 of att-1 a second time within the same call.
        candidates.add(new CandidateRecord("Biedronka", "Miasto Testowe", "Ulica Testowa", "1",
                "00-001", "Ulica Testowa 1, 00-001 Miasto Testowe", "att-1", 1));

        CreateOutcome outcome = service.createDataset("request-1", 1, 0, List.of("att-1"), candidates);

        assertThat(outcome.dataset().stores()).hasSize(3);
        assertThat(outcome.duplicateCount()).isEqualTo(1);
    }

    // TEST H: second verification pass reports a wildly different count -> workflow does NOT
    // proceed; the pass is rejected outright and the dataset is untouched.
    @Test
    void verificationReportingAWildlyDifferentCountIsRejectedAsAnInvariantViolation() {
        StoreAuditDatasetService service = service();
        StoreAuditDataset dataset = service.createDataset("request-1", 1, 0, List.of("att-1"), candidates(23, "att-1")).dataset();

        List<VerificationEntry> bogusVerification = new java.util.ArrayList<>();
        for (int index = 0; index < 117; index++) {
            bogusVerification.add(new VerificationEntry("store-" + String.format("%03d", index + 1), "VERIFIED", "", ""));
        }

        VerifyOutcome outcome = service.verifyDataset(dataset.datasetId(), bogusVerification);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.invariantViolation()).isTrue();
        Optional<StoreAuditDataset> stillLocked = service.getDataset(dataset.datasetId());
        assertThat(stillLocked).isPresent();
        assertThat(stillLocked.get().stores()).hasSize(23);
        assertThat(stillLocked.get().stage()).isEqualTo(DatasetStage.EXTRACTED);
    }

    @Test
    void verificationReferencingAnUnknownRecordIdIsRejectedAsAnInvariantViolation() {
        StoreAuditDatasetService service = service();
        StoreAuditDataset dataset = service.createDataset("request-1", 1, 0, List.of("att-1"), candidates(3, "att-1")).dataset();

        VerifyOutcome outcome = service.verifyDataset(dataset.datasetId(), List.of(new VerificationEntry("store-999", "VERIFIED", "", "")));

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.invariantViolation()).isTrue();
        assertThat(outcome.unknownRecordIds()).containsExactly("store-999");
    }

    // TEST I: canonical dataset survives unchanged across multiple calls into the service, as
    // would happen across multiple tool-loop turns/model calls referencing it by id.
    @Test
    void datasetSurvivesUnchangedAcrossMultipleServiceCallsSimulatingToolLoopContinuation() {
        StoreAuditDatasetService service = service();
        StoreAuditDataset dataset = service.createDataset("request-1", 1, 0, List.of("att-1"), candidates(23, "att-1")).dataset();

        // Simulate several intervening "model turns" that only read the dataset.
        for (int turn = 0; turn < 5; turn++) {
            assertThat(service.getDataset(dataset.datasetId()).orElseThrow().stores()).hasSize(23);
        }

        VerifyOutcome verify = service.verifyDataset(dataset.datasetId(),
                List.of(new VerificationEntry(dataset.stores().get(0).id(), "VERIFIED", "", "")));
        assertThat(verify.dataset().stores()).hasSize(23);

        GeolocationUpdateOutcome geo = service.updateGeolocation(dataset.datasetId(),
                List.of(new GeolocationEntry(dataset.stores().get(0).id(), GeolocationStatus.RESOLVED, 52.0, 21.0)));
        assertThat(geo.dataset().stores()).hasSize(23);
    }

    // TEST 9 (count invariant): a valid schedule referencing every store id exactly once is accepted.
    @Test
    void submitScheduleAcceptsAValidCompleteGrouping() {
        StoreAuditDatasetService service = serviceAt(FIXED_NOW);
        StoreAuditDataset dataset = service.createDataset("request-1", 1, 0, List.of("att-1"), candidates(23, "att-1")).dataset();
        List<String> ids = dataset.stores().stream().map(StoreRecord::id).toList();
        service.setPreferences(dataset.datasetId(), anyDayPreferences(2026, 6));

        ScheduleSubmitOutcome outcome = service.submitSchedule(dataset.datasetId(), List.of(
                scheduleDay(1, LocalDate.of(2026, 6, 2), ids.subList(0, 12)),
                scheduleDay(2, LocalDate.of(2026, 6, 3), ids.subList(12, 23))
        ));

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.dataset().stage()).isEqualTo(DatasetStage.SCHEDULED);
        assertThat(outcome.dataset().schedule()).hasSize(2);
        assertThat(outcome.dataset().schedule().get(0).dayOfWeek()).isEqualTo(DayOfWeek.TUESDAY);
    }

    // TEST 5: 23 stores, schedule contains only 22 -> rejected as invalid, nothing applied.
    @Test
    void submitScheduleRejectsAGroupingMissingAStore() {
        StoreAuditDatasetService service = serviceAt(FIXED_NOW);
        StoreAuditDataset dataset = service.createDataset("request-1", 1, 0, List.of("att-1"), candidates(23, "att-1")).dataset();
        List<String> ids = new java.util.ArrayList<>(dataset.stores().stream().map(StoreRecord::id).toList());
        String omitted = ids.remove(22);
        service.setPreferences(dataset.datasetId(), anyDayPreferences(2026, 6));

        ScheduleSubmitOutcome outcome = service.submitSchedule(dataset.datasetId(),
                List.of(scheduleDay(1, LocalDate.of(2026, 6, 2), ids)));

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.invariantViolation()).isTrue();
        assertThat(outcome.missingStoreIds()).containsExactly(omitted);
        assertThat(service.getDataset(dataset.datasetId()).orElseThrow().stage()).isEqualTo(DatasetStage.EXTRACTED);
    }

    // TEST 6: 23 stores, schedule references an unknown/hallucinated id -> rejected.
    @Test
    void submitScheduleRejectsAnUnknownHallucinatedStoreId() {
        StoreAuditDatasetService service = serviceAt(FIXED_NOW);
        StoreAuditDataset dataset = service.createDataset("request-1", 1, 0, List.of("att-1"), candidates(23, "att-1")).dataset();
        List<String> ids = new java.util.ArrayList<>(dataset.stores().stream().map(StoreRecord::id).toList());
        ids.add("store-999");
        service.setPreferences(dataset.datasetId(), anyDayPreferences(2026, 6));

        ScheduleSubmitOutcome outcome = service.submitSchedule(dataset.datasetId(),
                List.of(scheduleDay(1, LocalDate.of(2026, 6, 2), ids)));

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.unknownStoreIds()).containsExactly("store-999");
    }

    // TEST 4: the same store id scheduled twice -> rejected as a duplicate.
    @Test
    void submitScheduleRejectsADuplicateStoreIdAcrossDays() {
        StoreAuditDatasetService service = serviceAt(FIXED_NOW);
        StoreAuditDataset dataset = service.createDataset("request-1", 1, 0, List.of("att-1"), candidates(3, "att-1")).dataset();
        List<String> ids = dataset.stores().stream().map(StoreRecord::id).toList();
        service.setPreferences(dataset.datasetId(), anyDayPreferences(2026, 6));

        ScheduleSubmitOutcome outcome = service.submitSchedule(dataset.datasetId(), List.of(
                scheduleDay(1, LocalDate.of(2026, 6, 2), List.of(ids.get(0), ids.get(1))),
                scheduleDay(2, LocalDate.of(2026, 6, 3), List.of(ids.get(1), ids.get(2)))
        ));

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.duplicateStoreIds()).containsExactly(ids.get(1));
    }

    @Test
    void submitScheduleUnknownDatasetIdFails() {
        StoreAuditDatasetService service = service();

        ScheduleSubmitOutcome outcome = service.submitSchedule("does-not-exist",
                List.of(scheduleDay(1, LocalDate.of(2026, 6, 2), List.of("store-001"))));

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.dataset()).isNull();
    }

    // =====================================================================
    // New tests: scheduling preferences and date-aware SUBMIT_SCHEDULE validation.
    // =====================================================================

    @Test
    void setPreferencesDefaultsYearToTheCurrentYearWhenOmitted() {
        StoreAuditDatasetService service = serviceAt(FIXED_NOW);
        StoreAuditDataset dataset = service.createDataset("request-1", 1, 0, List.of("att-1"), candidates(3, "att-1")).dataset();

        PreferencesOutcome outcome = service.setPreferences(dataset.datasetId(),
                new SchedulingPreferences(0, 6, EnumSet.of(DayOfWeek.TUESDAY), Set.of(), DistributionStrategy.EVEN, null, null, false));

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.dataset().preferences().year()).isEqualTo(2026);
    }

    @Test
    void setPreferencesRejectsAMonthThatHasAlreadyPassed() {
        StoreAuditDatasetService service = serviceAt(FIXED_NOW);
        StoreAuditDataset dataset = service.createDataset("request-1", 1, 0, List.of("att-1"), candidates(3, "att-1")).dataset();

        PreferencesOutcome outcome = service.setPreferences(dataset.datasetId(),
                new SchedulingPreferences(2026, 5, EnumSet.of(DayOfWeek.TUESDAY), Set.of(), DistributionStrategy.EVEN, null, null, false));

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.errorCode()).isEqualTo("STORE_AUDIT_PREFERENCES_MONTH_IN_PAST");
    }

    @Test
    void setPreferencesRejectsNoDaysOfWeekAtAll() {
        StoreAuditDatasetService service = serviceAt(FIXED_NOW);
        StoreAuditDataset dataset = service.createDataset("request-1", 1, 0, List.of("att-1"), candidates(3, "att-1")).dataset();

        PreferencesOutcome outcome = service.setPreferences(dataset.datasetId(),
                new SchedulingPreferences(2026, 6, Set.of(), Set.of(), DistributionStrategy.EVEN, null, null, false));

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.errorCode()).isEqualTo("STORE_AUDIT_PREFERENCES_NO_DAYS");
    }

    @Test
    void submitScheduleRejectsWhenPreferencesWereNeverSet() {
        StoreAuditDatasetService service = serviceAt(FIXED_NOW);
        StoreAuditDataset dataset = verifyAll(service, service.createDataset("request-1", 1, 0, List.of("att-1"), candidates(1, "att-1")).dataset());
        List<String> ids = dataset.stores().stream().map(StoreRecord::id).toList();

        ScheduleSubmitOutcome outcome = service.submitSchedule(dataset.datasetId(),
                List.of(scheduleDay(1, LocalDate.of(2026, 6, 2), ids)));

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).contains("SET_PREFERENCES");
    }

    @Test
    void submitScheduleRejectsADateBeforeToday() {
        StoreAuditDatasetService service = serviceAt(FIXED_NOW);
        StoreAuditDataset dataset = service.createDataset("request-1", 1, 0, List.of("att-1"), candidates(1, "att-1")).dataset();
        List<String> ids = dataset.stores().stream().map(StoreRecord::id).toList();
        service.setPreferences(dataset.datasetId(), anyDayPreferences(2026, 6));

        ScheduleSubmitOutcome outcome = service.submitSchedule(dataset.datasetId(),
                List.of(scheduleDay(1, LocalDate.of(2026, 5, 31), ids)));

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).contains("past");
    }

    @Test
    void submitScheduleRejectsADateOutsideTheAgreedMonth() {
        StoreAuditDatasetService service = serviceAt(FIXED_NOW);
        StoreAuditDataset dataset = service.createDataset("request-1", 1, 0, List.of("att-1"), candidates(1, "att-1")).dataset();
        List<String> ids = dataset.stores().stream().map(StoreRecord::id).toList();
        service.setPreferences(dataset.datasetId(), anyDayPreferences(2026, 6));

        ScheduleSubmitOutcome outcome = service.submitSchedule(dataset.datasetId(),
                List.of(scheduleDay(1, LocalDate.of(2026, 7, 1), ids)));

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).contains("planning window");
    }

    @Test
    void submitScheduleRejectsADayOfWeekNotInTheAgreedSet() {
        StoreAuditDatasetService service = serviceAt(FIXED_NOW);
        StoreAuditDataset dataset = service.createDataset("request-1", 1, 0, List.of("att-1"), candidates(1, "att-1")).dataset();
        List<String> ids = dataset.stores().stream().map(StoreRecord::id).toList();
        service.setPreferences(dataset.datasetId(), new SchedulingPreferences(2026, 6, EnumSet.of(DayOfWeek.TUESDAY),
                Set.of(), DistributionStrategy.EVEN, null, null, false));

        // 2026-06-04 is a Thursday - not in the agreed {TUESDAY} set.
        ScheduleSubmitOutcome outcome = service.submitSchedule(dataset.datasetId(),
                List.of(scheduleDay(1, LocalDate.of(2026, 6, 4), ids)));

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).contains("THURSDAY");
    }

    @Test
    void submitScheduleRejectsAnEmptyDay() {
        StoreAuditDatasetService service = serviceAt(FIXED_NOW);
        StoreAuditDataset dataset = service.createDataset("request-1", 1, 0, List.of("att-1"), candidates(1, "att-1")).dataset();
        service.setPreferences(dataset.datasetId(), anyDayPreferences(2026, 6));

        ScheduleSubmitOutcome outcome = service.submitSchedule(dataset.datasetId(),
                List.of(scheduleDay(1, LocalDate.of(2026, 6, 2), List.of())));

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).contains("no stores assigned");
    }

    @Test
    void submitScheduleRejectsANegativeRouteDistance() {
        StoreAuditDatasetService service = serviceAt(FIXED_NOW);
        StoreAuditDataset dataset = service.createDataset("request-1", 1, 0, List.of("att-1"), candidates(1, "att-1")).dataset();
        List<String> ids = dataset.stores().stream().map(StoreRecord::id).toList();
        service.setPreferences(dataset.datasetId(), anyDayPreferences(2026, 6));

        ScheduleSubmitOutcome outcome = service.submitSchedule(dataset.datasetId(),
                List.of(new ScheduleDay(1, LocalDate.of(2026, 6, 2), ids, -1d, 100d, 100d)));

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).contains("negative");
    }

    @Test
    void submitScheduleAcceptsAnExplicitDateRangeOverridingTheMonthWindow() {
        StoreAuditDatasetService service = serviceAt(FIXED_NOW);
        StoreAuditDataset dataset = service.createDataset("request-1", 1, 0, List.of("att-1"), candidates(1, "att-1")).dataset();
        List<String> ids = dataset.stores().stream().map(StoreRecord::id).toList();
        service.setPreferences(dataset.datasetId(), new SchedulingPreferences(2026, 6, EnumSet.allOf(DayOfWeek.class),
                Set.of(), DistributionStrategy.EVEN, LocalDate.of(2026, 6, 2), LocalDate.of(2026, 6, 5), false));

        ScheduleSubmitOutcome outcome = service.submitSchedule(dataset.datasetId(),
                List.of(scheduleDay(1, LocalDate.of(2026, 6, 5), ids)));

        assertThat(outcome.success()).isTrue();
    }

    @Test
    void defaultStylePreferencesAllowTuesdayWednesdayAndMondayAsFallbackButNothingElse() {
        // Damian's default preference shape: Tuesday/Wednesday preferred, Monday as a fallback
        // day only - never automatically extended to Thursday-Sunday.
        SchedulingPreferences preferences = new SchedulingPreferences(2026, 6,
                EnumSet.of(DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY), EnumSet.of(DayOfWeek.MONDAY),
                DistributionStrategy.EVEN, null, null, false);

        assertThat(preferences.allowedDaysOfWeek()).containsExactlyInAnyOrder(
                DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY);
        assertThat(preferences.allowedDaysOfWeek()).doesNotContain(
                DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
    }

    @Test
    void submitScheduleRejectsASaturdayWithoutExplicitConsent() {
        StoreAuditDatasetService service = serviceAt(FIXED_NOW);
        StoreAuditDataset dataset = service.createDataset("request-1", 1, 0, List.of("att-1"), candidates(1, "att-1")).dataset();
        List<String> ids = dataset.stores().stream().map(StoreRecord::id).toList();
        service.setPreferences(dataset.datasetId(), new SchedulingPreferences(2026, 6,
                EnumSet.of(DayOfWeek.TUESDAY), Set.of(), DistributionStrategy.EVEN, null, null, false));

        // 2026-06-06 is a Saturday.
        ScheduleSubmitOutcome outcome = service.submitSchedule(dataset.datasetId(),
                List.of(scheduleDay(1, LocalDate.of(2026, 6, 6), ids)));

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.message()).contains("SATURDAY");
    }

    @Test
    void submitScheduleAcceptsASaturdayWhenExplicitlyAllowed() {
        StoreAuditDatasetService service = serviceAt(FIXED_NOW);
        StoreAuditDataset dataset = service.createDataset("request-1", 1, 0, List.of("att-1"), candidates(1, "att-1")).dataset();
        List<String> ids = dataset.stores().stream().map(StoreRecord::id).toList();
        service.setPreferences(dataset.datasetId(), new SchedulingPreferences(2026, 6,
                EnumSet.of(DayOfWeek.TUESDAY), Set.of(), DistributionStrategy.EVEN, null, null, true));

        ScheduleSubmitOutcome outcome = service.submitSchedule(dataset.datasetId(),
                List.of(scheduleDay(1, LocalDate.of(2026, 6, 6), ids)));

        assertThat(outcome.success()).isTrue();
    }

    @Test
    void submitScheduleAcceptsEveryTuesdayAcrossAFourTuesdayMonth() {
        // February 2027 has exactly 4 Tuesdays (2,9,16,23) - a month spanning exactly 4 weeks for
        // that day of week.
        StoreAuditDatasetService service = serviceAt(FIXED_NOW);
        StoreAuditDataset dataset = service.createDataset("request-1", 1, 0, List.of("att-1"), candidates(4, "att-1")).dataset();
        List<String> ids = dataset.stores().stream().map(StoreRecord::id).toList();
        service.setPreferences(dataset.datasetId(), new SchedulingPreferences(2027, 2,
                EnumSet.of(DayOfWeek.TUESDAY), Set.of(), DistributionStrategy.EVEN, null, null, false));

        ScheduleSubmitOutcome outcome = service.submitSchedule(dataset.datasetId(), List.of(
                scheduleDay(1, LocalDate.of(2027, 2, 2), List.of(ids.get(0))),
                scheduleDay(2, LocalDate.of(2027, 2, 9), List.of(ids.get(1))),
                scheduleDay(3, LocalDate.of(2027, 2, 16), List.of(ids.get(2))),
                scheduleDay(4, LocalDate.of(2027, 2, 23), List.of(ids.get(3)))
        ));

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.dataset().schedule()).extracting(ScheduleDay::dayOfWeek).containsOnly(DayOfWeek.TUESDAY);
    }

    @Test
    void submitScheduleAcceptsEveryTuesdayAcrossAFiveTuesdayMonth() {
        // March 2027 has 5 Tuesdays (2,9,16,23,30) - a month spanning 5 weeks for that day of week.
        StoreAuditDatasetService service = serviceAt(FIXED_NOW);
        StoreAuditDataset dataset = service.createDataset("request-1", 1, 0, List.of("att-1"), candidates(5, "att-1")).dataset();
        List<String> ids = dataset.stores().stream().map(StoreRecord::id).toList();
        service.setPreferences(dataset.datasetId(), new SchedulingPreferences(2027, 3,
                EnumSet.of(DayOfWeek.TUESDAY), Set.of(), DistributionStrategy.EVEN, null, null, false));

        ScheduleSubmitOutcome outcome = service.submitSchedule(dataset.datasetId(), List.of(
                scheduleDay(1, LocalDate.of(2027, 3, 2), List.of(ids.get(0))),
                scheduleDay(2, LocalDate.of(2027, 3, 9), List.of(ids.get(1))),
                scheduleDay(3, LocalDate.of(2027, 3, 16), List.of(ids.get(2))),
                scheduleDay(4, LocalDate.of(2027, 3, 23), List.of(ids.get(3))),
                scheduleDay(5, LocalDate.of(2027, 3, 30), List.of(ids.get(4)))
        ));

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.dataset().schedule()).hasSize(5);
    }

    @Test
    void scheduleDayTotalWorkSecondsIncludesTravelAndAuditTime() {
        ScheduleDay day = new ScheduleDay(1, LocalDate.of(2026, 6, 2), List.of("store-001"), 15000d, 1800d, 3600d);

        assertThat(day.totalWorkSeconds()).isEqualTo(5400d);
        assertThat(day.dayOfWeek()).isEqualTo(DayOfWeek.TUESDAY);
    }

    @Test
    void requestUserInputRecordsAndSubsequentRealProgressClearsThePause() {
        StoreAuditDatasetService service = serviceAt(FIXED_NOW);
        StoreAuditDataset dataset = verifyAll(service, service.createDataset("request-1", 1, 0, List.of("att-1"), candidates(1, "att-1")).dataset());

        PauseOutcome paused = service.requestUserInput(dataset.datasetId(), WorkflowPause.AWAITING_DECISION, "5 Biedronki blisko siebie");
        assertThat(paused.success()).isTrue();
        assertThat(service.getDataset(dataset.datasetId()).orElseThrow().pendingUserInput()).isEqualTo(WorkflowPause.AWAITING_DECISION);

        service.setPreferences(dataset.datasetId(), anyDayPreferences(2026, 6));
        assertThat(service.getDataset(dataset.datasetId()).orElseThrow().pendingUserInput()).isEqualTo(WorkflowPause.NONE);
    }

    // TEST 7 (conversation continuity): a dataset registered against a conversation can be found
    // again in a later turn without knowing its exact datasetId, the same way a second chat message
    // in the same conversation would look it up.
    @Test
    void findLatestForConversationLocatesADatasetCreatedInAnEarlierTurnWithoutResendingAttachments() {
        StoreAuditDatasetService service = service();
        service.registerAttachments("request-1", "conversation-42", List.of("att-1"));
        StoreAuditDataset dataset = service.createDataset("request-1", 1, 0, List.of("att-1"), candidates(23, "att-1")).dataset();

        Optional<StoreAuditDataset> found = service.findLatestForConversation("conversation-42");

        assertThat(found).isPresent();
        assertThat(found.get().datasetId()).isEqualTo(dataset.datasetId());
        assertThat(found.get().stores()).hasSize(23);
    }

    @Test
    void findLatestForConversationReturnsTheMostRecentDatasetWhenSeveralExistForTheSameConversation() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        StoreAuditDatasetService service = new StoreAuditDatasetService(new NoopCognitiveEventBus(), clock);
        service.registerAttachments("request-1", "conversation-42", List.of("att-1"));
        service.createDataset("request-1", 1, 0, List.of("att-1"), candidates(3, "att-1"));

        clock.advance(Duration.ofMinutes(5));
        service.registerAttachments("request-2", "conversation-42", List.of("att-2"));
        StoreAuditDataset second = service.createDataset("request-2", 1, 0, List.of("att-2"), candidates(5, "att-2")).dataset();

        Optional<StoreAuditDataset> found = service.findLatestForConversation("conversation-42");

        assertThat(found).isPresent();
        assertThat(found.get().datasetId()).isEqualTo(second.datasetId());
    }

    @Test
    void findLatestForConversationNeverMatchesADatasetFromADifferentConversation() {
        StoreAuditDatasetService service = service();
        service.registerAttachments("request-1", "conversation-A", List.of("att-1"));
        service.createDataset("request-1", 1, 0, List.of("att-1"), candidates(3, "att-1"));

        assertThat(service.findLatestForConversation("conversation-B")).isEmpty();
        assertThat(service.findLatestForConversation("")).isEmpty();
    }

    @Test
    void expiredDatasetIsSweptAndNoLongerRetrievable() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        StoreAuditDatasetService service = new StoreAuditDatasetService(new NoopCognitiveEventBus(), clock);
        StoreAuditDataset dataset = service.createDataset("request-1", 1, 0, List.of("att-1"), candidates(3, "att-1")).dataset();

        clock.advance(Duration.ofHours(3));

        assertThat(service.getDataset(dataset.datasetId())).isEmpty();
    }

    // =====================================================================
    // Regression tests for the multi-attachment provenance / expectedRecordCount /
    // full-N/N-verification / VERIFY-before-GEOCODE hardening round (see also LocationToolTest
    // for the canonical GEOCODE_DATASET tests, and NativeToolLoopServiceCompletionGateTest for the
    // completion-gate-on-every-exit-path tests). None of these hardcode a specific store chain,
    // month, or count beyond what each scenario needs - the production bug used 14+9=23 Biedronka/
    // Stokrotka records purely as an illustrative fixture size.
    // =====================================================================

    // REGRESSION TEST 1: two real, registered current-message attachments - START_DATASET declares
    // only the first batch's records (image-A), APPEND_RECORDS supplies the second batch (image-B).
    // Every record from BOTH attachments must be accepted; a dataset scoped to 2 real attachments
    // must never silently only allow records from the first one.
    @Test
    void multiAttachmentProvenanceAcceptsRecordsFromEveryRealAttachmentAcrossIncrementalBatches() {
        StoreAuditDatasetService service = service();
        service.registerAttachments("request-1", "conversation-1", List.of("image-A", "image-B"));

        // START_DATASET's own sourceAttachmentIds argument only mentions image-A - exactly the
        // production bug's shape - but Core's real registered set (both image-A and image-B) is
        // what actually governs provenance, not this possibly-incomplete model declaration.
        CreateOutcome started = service.startDataset("request-1", 2, 23, List.of("image-A"), candidates(14, "image-A"));
        assertThat(started.success()).isTrue();
        assertThat(started.dataset().sourceAttachmentIds()).containsExactlyInAnyOrder("image-A", "image-B");
        String datasetId = started.dataset().datasetId();

        List<CandidateRecord> imageBBatch = new java.util.ArrayList<>();
        for (int index = 15; index <= 23; index++) {
            imageBBatch.add(new CandidateRecord("Stokrotka", "Miasto Testowe", "Ulica Testowa", String.valueOf(index),
                    "00-00" + (index % 10), "Ulica Testowa " + index + ", Miasto Testowe", "image-B", index));
        }
        AppendOutcome appended = service.appendRecords(datasetId, imageBBatch);

        assertThat(appended.success()).isTrue();
        assertThat(appended.acceptedCount()).isEqualTo(9);
        assertThat(appended.rejected()).isEmpty();
        assertThat(appended.dataset().stores()).hasSize(23);

        FinalizeOutcome finalized = service.finalizeDataset(datasetId);
        assertThat(finalized.success()).isTrue();
        assertThat(finalized.dataset().stores()).hasSize(23);
    }

    // REGRESSION TEST 2: expectedRecordCount=23 declared at START_DATASET time, but only 14 records
    // actually accepted - FINALIZE_DATASET must reject, dataset stays BUILDING.
    @Test
    void finalizeRejectsAnIncompleteExtractionShortOfTheDeclaredExpectedCount() {
        StoreAuditDatasetService service = service();
        String datasetId = service.startDataset("request-1", 2, 23, List.of("att-1"), candidates(14, "att-1"))
                .dataset().datasetId();

        FinalizeOutcome outcome = service.finalizeDataset(datasetId);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.errorCode()).isEqualTo("STORE_DATASET_INCOMPLETE_EXTRACTION");
        assertThat(outcome.message()).contains("23").contains("14");
        assertThat(service.getDataset(datasetId).orElseThrow().stage()).isEqualTo(DatasetStage.BUILDING);
    }

    // REGRESSION TEST 3: expectedRecordCount=23, actual=23 - FINALIZE_DATASET succeeds, stage=EXTRACTED.
    @Test
    void finalizeAcceptsAnExtractionThatMatchesTheDeclaredExpectedCountExactly() {
        StoreAuditDatasetService service = service();
        String datasetId = service.startDataset("request-1", 2, 23, List.of("att-1"), candidates(23, "att-1"))
                .dataset().datasetId();

        FinalizeOutcome outcome = service.finalizeDataset(datasetId);

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.dataset().stage()).isEqualTo(DatasetStage.EXTRACTED);
        assertThat(outcome.dataset().stores()).hasSize(23);
    }

    // REGRESSION TEST 4: GEOCODE_DATASET (via updateGeolocation) on a merely-EXTRACTED (not yet
    // verified) dataset must be rejected outright - VERIFY_DATASET is mandatory first.
    @Test
    void geolocationIsRejectedOnAnUnverifiedExtractedDataset() {
        StoreAuditDatasetService service = service();
        StoreAuditDataset dataset = service.createDataset("request-1", 1, 0, List.of("att-1"), candidates(23, "att-1")).dataset();
        assertThat(dataset.stage()).isEqualTo(DatasetStage.EXTRACTED);

        GeolocationUpdateOutcome outcome = service.updateGeolocation(dataset.datasetId(),
                List.of(new GeolocationEntry(dataset.stores().get(0).id(), GeolocationStatus.RESOLVED, 52.0, 21.0)));

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.errorCode()).isEqualTo("STORE_DATASET_NOT_VERIFIED");
        assertThat(service.getDataset(dataset.datasetId()).orElseThrow().stage()).isEqualTo(DatasetStage.EXTRACTED);
    }

    // REGRESSION TEST 5: a 23-record dataset, VERIFY_DATASET called with only 1 of the 23 canonical
    // ids - must be rejected outright as incomplete, never silently accepted as "verified".
    @Test
    void verifyDatasetRejectsAPartialPassCoveringOnlyOneOfManyRecords() {
        StoreAuditDatasetService service = service();
        StoreAuditDataset dataset = service.createDataset("request-1", 1, 0, List.of("att-1"), candidates(23, "att-1")).dataset();

        VerifyOutcome outcome = service.verifyDataset(dataset.datasetId(),
                List.of(new VerificationEntry(dataset.stores().get(0).id(), "VERIFIED", "", "")));

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.invariantViolation()).isTrue();
        assertThat(outcome.missingRecordIds()).hasSize(22);
        assertThat(service.getDataset(dataset.datasetId()).orElseThrow().stage()).isEqualTo(DatasetStage.EXTRACTED);
    }

    // REGRESSION TEST 6: a verification entry with a malformed id ("013" instead of the real
    // "store-013") must be reported as an exact unknown id, and the whole pass rejected - a model
    // typo can never silently drop a record from verification.
    @Test
    void verifyDatasetRejectsAMalformedRecordIdAsUnknownRatherThanMatchingItLoosely() {
        StoreAuditDatasetService service = service();
        StoreAuditDataset dataset = service.createDataset("request-1", 1, 0, List.of("att-1"), candidates(23, "att-1")).dataset();
        List<VerificationEntry> entries = new java.util.ArrayList<>();
        for (StoreRecord record : dataset.stores()) {
            // Record 13 ("store-013") is mistyped as the bare number "013".
            String id = record.id().equals("store-013") ? "013" : record.id();
            entries.add(new VerificationEntry(id, "VERIFIED", "", ""));
        }

        VerifyOutcome outcome = service.verifyDataset(dataset.datasetId(), entries);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.unknownRecordIds()).containsExactly("013");
        assertThat(outcome.missingRecordIds()).containsExactly("store-013");
        assertThat(service.getDataset(dataset.datasetId()).orElseThrow().stage()).isEqualTo(DatasetStage.EXTRACTED);
    }

    // REGRESSION TEST 7: a full 23/23 verification pass - every canonical id, exactly once -
    // succeeds and advances the dataset to LOCKED.
    @Test
    void verifyDatasetAcceptsAFullPassCoveringEveryCanonicalRecordExactlyOnceAndLocksTheDataset() {
        StoreAuditDatasetService service = service();
        StoreAuditDataset dataset = service.createDataset("request-1", 1, 0, List.of("att-1"), candidates(23, "att-1")).dataset();

        StoreAuditDataset locked = verifyAll(service, dataset);

        assertThat(locked.stage()).isEqualTo(DatasetStage.LOCKED);
        assertThat(locked.stores()).hasSize(23);
        assertThat(locked.stores()).allMatch(record -> record.verificationStatus() == VerificationStatus.VERIFIED);
    }

    // =====================================================================
    // Regression tests for Core-owned sourceAttachmentIndex provenance (round 2): the model
    // supplies a 1-based position into the current message's REAL attachment list instead of
    // having to copy a real attachment UUID by hand - see also StoreDatasetToolTest and
    // NativeToolLoopServiceCompletionGateTest for the surrounding tool-schema/loop-level coverage.
    // =====================================================================

    // TEST 1: 2 real, registered current-message attachments - every candidate references them by
    // 1-based index (1 = image-A, 2 = image-B) instead of by real attachment id. Core must resolve
    // each index to the real attachment id deterministically and accept the dataset.
    @Test
    void sourceAttachmentIndexResolvesDeterministicallyToTheRealRegisteredAttachmentIds() {
        StoreAuditDatasetService service = service();
        service.registerAttachments("request-1", "conversation-1", List.of("image-A", "image-B"));

        List<CandidateRecord> candidates = candidatesByIndex(4, 1, 2);
        CreateOutcome outcome = service.startDataset("request-1", 2, 4, List.of(), candidates);

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.dataset().stores()).extracting(StoreRecord::sourceAttachmentId)
                .containsExactlyInAnyOrder("image-A", "image-B", "image-A", "image-B");
    }

    // TEST 2: sourceAttachmentIndex=3 with only 2 real attachments registered - rejected outright,
    // with a precise errorCode, never silently mapped to an out-of-range/guessed attachment.
    @Test
    void sourceAttachmentIndexOutOfRangeIsRejectedWithAPreciseErrorCodeAndNoSilentFallback() {
        StoreAuditDatasetService service = service();
        service.registerAttachments("request-1", "conversation-1", List.of("image-A", "image-B"));
        List<CandidateRecord> candidates = new java.util.ArrayList<>(candidatesByIndex(2, 1));
        candidates.add(new CandidateRecord("Biedronka", "Miasto Testowe", "Ulica Testowa", "99",
                "00-099", "Ulica Testowa 99, 00-099 Miasto Testowe", "", 99, 3));

        CreateOutcome outcome = service.startDataset("request-1", 2, 3, List.of(), candidates);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.errorCode()).isEqualTo("STORE_DATASET_ATTACHMENT_INDEX_INVALID");
        assertThat(outcome.message()).contains("[3]").contains("1..2");
        assertThat(outcome.dataset()).isNull();
    }

    // TEST 3: 23 records (14 via index=1, 9 via index=2) through the full incremental flow - the
    // final accepted count is 23 and every record's internal sourceAttachmentId is a real,
    // registered attachment id (never blank, never a copied/echoed value from the model).
    @Test
    void twentyThreeRecordsAcrossTwoIndexedAttachmentsFlowThroughTheIncrementalDatasetIntact() {
        StoreAuditDatasetService service = service();
        service.registerAttachments("request-1", "conversation-1", List.of("image-A", "image-B"));

        CreateOutcome started = service.startDataset("request-1", 2, 23, List.of(), candidatesByIndex(14, 1));
        assertThat(started.success()).isTrue();
        String datasetId = started.dataset().datasetId();

        List<CandidateRecord> secondBatch = new java.util.ArrayList<>();
        for (int index = 15; index <= 23; index++) {
            secondBatch.add(new CandidateRecord("Stokrotka", "Miasto Testowe", "Ulica Testowa", String.valueOf(index),
                    "00-00" + (index % 10), "Ulica Testowa " + index + ", Miasto Testowe", "", index, 2));
        }
        AppendOutcome appended = service.appendRecords(datasetId, secondBatch);
        assertThat(appended.success()).isTrue();
        assertThat(appended.dataset().stores()).hasSize(23);

        FinalizeOutcome finalized = service.finalizeDataset(datasetId);
        assertThat(finalized.success()).isTrue();
        assertThat(finalized.dataset().stores()).hasSize(23);
        assertThat(finalized.dataset().stores()).allMatch(record ->
                record.sourceAttachmentId().equals("image-A") || record.sourceAttachmentId().equals("image-B"));
        long fromImageA = finalized.dataset().stores().stream().filter(record -> record.sourceAttachmentId().equals("image-A")).count();
        long fromImageB = finalized.dataset().stores().stream().filter(record -> record.sourceAttachmentId().equals("image-B")).count();
        assertThat(fromImageA).isEqualTo(14);
        assertThat(fromImageB).isEqualTo(9);
    }

    private StoreAuditDatasetService service() {
        return new StoreAuditDatasetService(new NoopCognitiveEventBus());
    }

    // A fixed "today" so schedule-date tests (which now require dates that are within the agreed
    // month and never in the past) are fully deterministic, never dependent on the machine's real
    // clock - 2026-06-01 (a Monday).
    private static final Instant FIXED_NOW = Instant.parse("2026-06-01T10:00:00Z");

    private StoreAuditDatasetService serviceAt(Instant now) {
        return new StoreAuditDatasetService(new NoopCognitiveEventBus(), Clock.fixed(now, ZoneOffset.UTC));
    }

    /**
     * Preferences allowing every day of week - used by tests that only care about the record-id
     * count invariants, not day-of-week alignment, so an arbitrary date within the target month
     * always satisfies {@link SchedulingPreferences#allowedDaysOfWeek()}.
     */
    private SchedulingPreferences anyDayPreferences(int year, int month) {
        return new SchedulingPreferences(year, month, EnumSet.allOf(DayOfWeek.class), java.util.Set.of(),
                DistributionStrategy.EVEN, null, null, false);
    }

    private ScheduleDay scheduleDay(int day, LocalDate date, List<String> storeIds) {
        return new ScheduleDay(day, date, storeIds, 1000d, 600d, 300d);
    }

    /**
     * Builds candidates referencing the given 1-based attachment indices (round-robin), leaving
     * {@code sourceAttachmentId} blank - exactly how a model using the preferred index-based
     * provenance field would submit them.
     */
    private List<CandidateRecord> candidatesByIndex(int count, int... attachmentIndices) {
        List<CandidateRecord> candidates = new java.util.ArrayList<>();
        int[] indices = attachmentIndices.length > 0 ? attachmentIndices : new int[] {1};
        for (int index = 1; index <= count; index++) {
            int attachmentIndex = indices[(index - 1) % indices.length];
            candidates.add(new CandidateRecord("Biedronka", "Miasto Testowe", "Ulica Testowa",
                    String.valueOf(index), "00-00" + (index % 10), "Ulica Testowa " + index + ", Miasto Testowe",
                    "", index, attachmentIndex));
        }
        return candidates;
    }

    /**
     * Submits a full, valid verification pass (every canonical record id, exactly once, VERIFIED)
     * so tests that only care about geolocation/scheduling behavior can reach stage=LOCKED without
     * each repeating the full-coverage verification pass by hand.
     */
    private StoreAuditDataset verifyAll(StoreAuditDatasetService service, StoreAuditDataset dataset) {
        List<VerificationEntry> entries = dataset.stores().stream()
                .map(record -> new VerificationEntry(record.id(), "VERIFIED", "", ""))
                .toList();
        VerifyOutcome outcome = service.verifyDataset(dataset.datasetId(), entries);
        assertThat(outcome.success()).isTrue();
        return outcome.dataset();
    }

    private List<CandidateRecord> candidates(int count, String... attachmentIds) {
        List<CandidateRecord> candidates = new java.util.ArrayList<>();
        String[] ids = attachmentIds.length > 0 ? attachmentIds : new String[] {"att-1"};
        for (int index = 1; index <= count; index++) {
            // Round-robins across every given attachment id, so a multi-attachment call (e.g.
            // candidates(23, "att-1", "att-2")) genuinely distributes records across all of them
            // instead of silently dumping everything under the first one - real coverage of every
            // declared attachment is itself an invariant StoreAuditDatasetService now enforces.
            String attachmentId = ids[(index - 1) % ids.length];
            candidates.add(new CandidateRecord("Biedronka", "Miasto Testowe", "Ulica Testowa",
                    String.valueOf(index), "00-00" + (index % 10), "Ulica Testowa " + index + ", Miasto Testowe",
                    attachmentId, index));
        }
        return candidates;
    }

    private static final class MutableClock extends Clock {

        private final AtomicReference<Instant> instant;
        private final ZoneId zone;

        private MutableClock(Instant instant) {
            this(new AtomicReference<>(instant), ZoneOffset.UTC);
        }

        private MutableClock(AtomicReference<Instant> instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        private void advance(Duration duration) {
            instant.updateAndGet(current -> current.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant.get();
        }
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
