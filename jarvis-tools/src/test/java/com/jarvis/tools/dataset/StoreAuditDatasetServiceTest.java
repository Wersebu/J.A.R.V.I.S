package com.jarvis.tools.dataset;

import com.jarvis.common.ai.BrainType;
import com.jarvis.common.event.CognitiveEvent;
import com.jarvis.common.event.CognitiveEventBus;
import com.jarvis.common.event.CognitiveEventType;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

        CreateOutcome outcome = service.createDataset("request-1", 2, List.of("att-1", "att-2"), candidates);

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

        CreateOutcome outcome = service.createDataset("request-1", 1, List.of("att-1"), legit);

        assertThat(outcome.dataset().stores()).hasSize(3);
        assertThat(outcome.rejected()).hasSize(1);
        assertThat(outcome.rejected().get(0).reason()).contains("provenance");
    }

    // TEST G: model attempts to introduce a StoreRecord without source provenance -> rejected.
    @Test
    void createDatasetRejectsDeclaredAttachmentIdsNotKnownToCoreAsRealAttachments() {
        StoreAuditDatasetService service = service();
        service.registerAttachments("request-1", List.of("att-1", "att-2"));

        CreateOutcome outcome = service.createDataset("request-1", 1, List.of("att-1", "att-fabricated"), candidates(3, "att-1"));

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.dataset()).isNull();
        assertThat(outcome.message()).contains("att-fabricated");
    }

    // TEST C: GeoLocation returns 23 results -> dataset remains 23 records.
    @Test
    void geolocationUpdatesResolveExistingRecordsWithoutChangingCount() {
        StoreAuditDatasetService service = service();
        StoreAuditDataset dataset = service.createDataset("request-1", 1, List.of("att-1"), candidates(23, "att-1")).dataset();

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
        StoreAuditDataset dataset = service.createDataset("request-1", 1, List.of("att-1"), candidates(23, "att-1")).dataset();

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
        StoreAuditDataset dataset = service.createDataset("request-1", 1, List.of("att-1"), candidates(23, "att-1")).dataset();
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

        CreateOutcome outcome = service.createDataset("request-1", 1, List.of("att-1"), candidates);

        assertThat(outcome.dataset().stores()).hasSize(3);
        assertThat(outcome.duplicateCount()).isEqualTo(1);
    }

    // TEST H: second verification pass reports a wildly different count -> workflow does NOT
    // proceed; the pass is rejected outright and the dataset is untouched.
    @Test
    void verificationReportingAWildlyDifferentCountIsRejectedAsAnInvariantViolation() {
        StoreAuditDatasetService service = service();
        StoreAuditDataset dataset = service.createDataset("request-1", 1, List.of("att-1"), candidates(23, "att-1")).dataset();

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
        StoreAuditDataset dataset = service.createDataset("request-1", 1, List.of("att-1"), candidates(3, "att-1")).dataset();

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
        StoreAuditDataset dataset = service.createDataset("request-1", 1, List.of("att-1"), candidates(23, "att-1")).dataset();

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
        StoreAuditDatasetService service = service();
        StoreAuditDataset dataset = service.createDataset("request-1", 1, List.of("att-1"), candidates(23, "att-1")).dataset();
        List<String> ids = dataset.stores().stream().map(StoreRecord::id).toList();

        ScheduleSubmitOutcome outcome = service.submitSchedule(dataset.datasetId(), List.of(
                new ScheduleDay(1, ids.subList(0, 12)),
                new ScheduleDay(2, ids.subList(12, 23))
        ));

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.dataset().stage()).isEqualTo(DatasetStage.SCHEDULED);
        assertThat(outcome.dataset().schedule()).hasSize(2);
    }

    // TEST 5: 23 stores, schedule contains only 22 -> rejected as invalid, nothing applied.
    @Test
    void submitScheduleRejectsAGroupingMissingAStore() {
        StoreAuditDatasetService service = service();
        StoreAuditDataset dataset = service.createDataset("request-1", 1, List.of("att-1"), candidates(23, "att-1")).dataset();
        List<String> ids = new java.util.ArrayList<>(dataset.stores().stream().map(StoreRecord::id).toList());
        String omitted = ids.remove(22);

        ScheduleSubmitOutcome outcome = service.submitSchedule(dataset.datasetId(), List.of(new ScheduleDay(1, ids)));

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.invariantViolation()).isTrue();
        assertThat(outcome.missingStoreIds()).containsExactly(omitted);
        assertThat(service.getDataset(dataset.datasetId()).orElseThrow().stage()).isEqualTo(DatasetStage.EXTRACTED);
    }

    // TEST 6: 23 stores, schedule references an unknown/hallucinated id -> rejected.
    @Test
    void submitScheduleRejectsAnUnknownHallucinatedStoreId() {
        StoreAuditDatasetService service = service();
        StoreAuditDataset dataset = service.createDataset("request-1", 1, List.of("att-1"), candidates(23, "att-1")).dataset();
        List<String> ids = new java.util.ArrayList<>(dataset.stores().stream().map(StoreRecord::id).toList());
        ids.add("store-999");

        ScheduleSubmitOutcome outcome = service.submitSchedule(dataset.datasetId(), List.of(new ScheduleDay(1, ids)));

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.unknownStoreIds()).containsExactly("store-999");
    }

    // TEST 4: the same store id scheduled twice -> rejected as a duplicate.
    @Test
    void submitScheduleRejectsADuplicateStoreIdAcrossDays() {
        StoreAuditDatasetService service = service();
        StoreAuditDataset dataset = service.createDataset("request-1", 1, List.of("att-1"), candidates(3, "att-1")).dataset();
        List<String> ids = dataset.stores().stream().map(StoreRecord::id).toList();

        ScheduleSubmitOutcome outcome = service.submitSchedule(dataset.datasetId(), List.of(
                new ScheduleDay(1, List.of(ids.get(0), ids.get(1))),
                new ScheduleDay(2, List.of(ids.get(1), ids.get(2)))
        ));

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.duplicateStoreIds()).containsExactly(ids.get(1));
    }

    @Test
    void submitScheduleUnknownDatasetIdFails() {
        StoreAuditDatasetService service = service();

        ScheduleSubmitOutcome outcome = service.submitSchedule("does-not-exist", List.of(new ScheduleDay(1, List.of("store-001"))));

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.dataset()).isNull();
    }

    // TEST 7 (conversation continuity): a dataset registered against a conversation can be found
    // again in a later turn without knowing its exact datasetId, the same way a second chat message
    // in the same conversation would look it up.
    @Test
    void findLatestForConversationLocatesADatasetCreatedInAnEarlierTurnWithoutResendingAttachments() {
        StoreAuditDatasetService service = service();
        service.registerAttachments("request-1", "conversation-42", List.of("att-1"));
        StoreAuditDataset dataset = service.createDataset("request-1", 1, List.of("att-1"), candidates(23, "att-1")).dataset();

        Optional<StoreAuditDataset> found = service.findLatestForConversation("conversation-42");

        assertThat(found).isPresent();
        assertThat(found.get().datasetId()).isEqualTo(dataset.datasetId());
        assertThat(found.get().stores()).hasSize(23);
    }

    @Test
    void findLatestForConversationReturnsTheMostRecentDatasetWhenSeveralExistForTheSameConversation() {
        StoreAuditDatasetService service = service();
        service.registerAttachments("request-1", "conversation-42", List.of("att-1"));
        service.createDataset("request-1", 1, List.of("att-1"), candidates(3, "att-1"));
        service.registerAttachments("request-2", "conversation-42", List.of("att-2"));
        StoreAuditDataset second = service.createDataset("request-2", 1, List.of("att-2"), candidates(5, "att-2")).dataset();

        Optional<StoreAuditDataset> found = service.findLatestForConversation("conversation-42");

        assertThat(found).isPresent();
        assertThat(found.get().datasetId()).isEqualTo(second.datasetId());
    }

    @Test
    void findLatestForConversationNeverMatchesADatasetFromADifferentConversation() {
        StoreAuditDatasetService service = service();
        service.registerAttachments("request-1", "conversation-A", List.of("att-1"));
        service.createDataset("request-1", 1, List.of("att-1"), candidates(3, "att-1"));

        assertThat(service.findLatestForConversation("conversation-B")).isEmpty();
        assertThat(service.findLatestForConversation("")).isEmpty();
    }

    @Test
    void expiredDatasetIsSweptAndNoLongerRetrievable() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        StoreAuditDatasetService service = new StoreAuditDatasetService(new NoopCognitiveEventBus(), clock);
        StoreAuditDataset dataset = service.createDataset("request-1", 1, List.of("att-1"), candidates(3, "att-1")).dataset();

        clock.advance(Duration.ofHours(3));

        assertThat(service.getDataset(dataset.datasetId())).isEmpty();
    }

    private StoreAuditDatasetService service() {
        return new StoreAuditDatasetService(new NoopCognitiveEventBus());
    }

    private List<CandidateRecord> candidates(int count, String... attachmentIds) {
        List<CandidateRecord> candidates = new java.util.ArrayList<>();
        String attachmentId = attachmentIds.length > 0 ? attachmentIds[0] : "att-1";
        for (int index = 1; index <= count; index++) {
            candidates.add(new CandidateRecord("Biedronka", "Miasto Testowe", "Ulica Testowa",
                    String.valueOf(index), "00-00" + (index % 10), "Ulica Testowa " + index + ", Miasto Testowe",
                    attachmentId, index));
        }
        return candidates;
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
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
