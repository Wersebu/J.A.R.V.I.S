package com.jarvis.tools.dataset;

import com.jarvis.common.ai.BrainType;
import com.jarvis.common.event.CognitiveEvent;
import com.jarvis.common.event.CognitiveEventBus;
import com.jarvis.common.event.CognitiveEventType;
import com.jarvis.tools.ToolRequest;
import com.jarvis.tools.ToolResult;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for the {@code storeDataset} tool's model-facing contract: honest
 * success/failure reporting, and that assigned record ids round-trip through CREATE -> VERIFY so
 * a model can reliably reference them.
 */
class StoreDatasetToolTest {

    @Test
    void createDatasetReturnsAssignedRecordIdsForLaterReference() {
        StoreDatasetTool tool = new StoreDatasetTool(new StoreAuditDatasetService(new NoopCognitiveEventBus()));

        ToolResult result = tool.execute(new ToolRequest("storeDataset", "CREATE_DATASET", "conversation-1", "request-1",
                "extraction", "", Map.of(
                        "sourceImageCount", 1,
                        "sourceAttachmentIds", List.of("att-1"),
                        "records", List.of(
                                Map.of("network", "Biedronka", "city", "Miasto Testowe", "street", "Ulica Testowa",
                                        "buildingNumber", "1", "postalCode", "00-001",
                                        "fullAddress", "Ulica Testowa 1, 00-001 Miasto Testowe",
                                        "sourceAttachmentId", "att-1", "sourceRow", 1),
                                Map.of("network", "Biedronka", "city", "Miasto Testowe", "street", "Ulica Testowa",
                                        "buildingNumber", "2", "postalCode", "00-001",
                                        "fullAddress", "Ulica Testowa 2, 00-001 Miasto Testowe",
                                        "sourceAttachmentId", "att-1", "sourceRow", 2)
                        )
                )));

        assertThat(result.success()).isTrue();
        assertThat(result.data().get("count")).isEqualTo(2);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> records = (List<Map<String, Object>>) result.data().get("records");
        assertThat(records).extracting(record -> record.get("id")).containsExactly("store-001", "store-002");
    }

    // TEST 1 (StoreDatasetTool half): a native tool call using sourceAttachmentIndex (1-based,
    // matching the "Image 1"/"Image 2" numbering shown to the model) instead of a real attachment
    // id - Core resolves each index against the real, registered current-message attachments.
    @Test
    void startDatasetResolvesSourceAttachmentIndexToTheRealRegisteredAttachmentIds() {
        StoreAuditDatasetService datasetService = new StoreAuditDatasetService(new NoopCognitiveEventBus());
        datasetService.registerAttachments("request-1", "conversation-1", List.of("image-A", "image-B"));
        StoreDatasetTool tool = new StoreDatasetTool(datasetService);

        ToolResult result = tool.execute(new ToolRequest("storeDataset", "START_DATASET", "conversation-1", "request-1",
                "extraction", "", Map.of(
                        "sourceImageCount", 2,
                        "expectedRecordCount", 2,
                        "sourceAttachmentIds", List.of(),
                        "records", List.of(
                                Map.of("network", "Biedronka", "fullAddress", "A 1", "sourceAttachmentIndex", 1, "sourceRow", 1),
                                Map.of("network", "Stokrotka", "fullAddress", "A 2", "sourceAttachmentIndex", 2, "sourceRow", 2)
                        )
                )));

        assertThat(result.success()).isTrue();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> records = (List<Map<String, Object>>) result.data().get("records");
        assertThat(records).extracting(record -> record.get("sourceAttachmentId"))
                .containsExactlyInAnyOrder("image-A", "image-B");
    }

    // TEST 2 (StoreDatasetTool half): sourceAttachmentIndex=3 with only 2 real attachments
    // registered - rejected outright with a precise errorCode, never silently mapped/guessed.
    @Test
    void startDatasetRejectsAnOutOfRangeSourceAttachmentIndex() {
        StoreAuditDatasetService datasetService = new StoreAuditDatasetService(new NoopCognitiveEventBus());
        datasetService.registerAttachments("request-1", "conversation-1", List.of("image-A", "image-B"));
        StoreDatasetTool tool = new StoreDatasetTool(datasetService);

        ToolResult result = tool.execute(new ToolRequest("storeDataset", "START_DATASET", "conversation-1", "request-1",
                "extraction", "", Map.of(
                        "sourceImageCount", 2,
                        "expectedRecordCount", 1,
                        "sourceAttachmentIds", List.of(),
                        "records", List.of(
                                Map.of("network", "Biedronka", "fullAddress", "A 1", "sourceAttachmentIndex", 3, "sourceRow", 1)
                        )
                )));

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("STORE_DATASET_ATTACHMENT_INDEX_INVALID");
    }

    @Test
    void createDatasetRejectsARecordWithoutValidProvenanceButKeepsTheValidOnes() {
        StoreDatasetTool tool = new StoreDatasetTool(new StoreAuditDatasetService(new NoopCognitiveEventBus()));

        ToolResult result = tool.execute(new ToolRequest("storeDataset", "CREATE_DATASET", "conversation-1", "request-1",
                "extraction", "", Map.of(
                        "sourceImageCount", 1,
                        "sourceAttachmentIds", List.of("att-1"),
                        "records", List.of(
                                Map.of("network", "Biedronka", "fullAddress", "Ulica Testowa 1, 00-001 Miasto Testowe",
                                        "sourceAttachmentId", "att-1", "sourceRow", 1),
                                // No sourceAttachmentId at all - e.g. copied from the workflow document's example.
                                Map.of("network", "Biedronka", "fullAddress", "Ulica Przykladowa 9, 00-009 Przyklad",
                                        "sourceRow", 2)
                        )
                )));

        assertThat(result.success()).isTrue();
        assertThat(result.data().get("count")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rejected = (List<Map<String, Object>>) result.data().get("rejected");
        assertThat(rejected).hasSize(1);
    }

    // End-to-end incremental build through the tool interface (not just the service directly):
    // START_DATASET -> APPEND_RECORDS -> FINALIZE_DATASET produces a dataset usable by
    // VERIFY_DATASET/SUBMIT_SCHEDULE exactly like a one-shot CREATE_DATASET would.
    @Test
    void startAppendFinalizeThroughTheToolProducesADatasetUsableBySubmitSchedule() {
        StoreAuditDatasetService service = fixedClockService();
        StoreDatasetTool tool = new StoreDatasetTool(service);

        ToolResult started = tool.execute(new ToolRequest("storeDataset", "START_DATASET", "conversation-1", "request-1",
                "extraction", "", Map.of(
                        "sourceImageCount", 1,
                        "sourceAttachmentIds", List.of("att-1"),
                        "records", List.of(
                                Map.of("network", "Biedronka", "fullAddress", "A 1", "sourceAttachmentId", "att-1", "sourceRow", 1),
                                Map.of("network", "Biedronka", "fullAddress", "A 2", "sourceAttachmentId", "att-1", "sourceRow", 2)
                        )
                )));
        assertThat(started.success()).isTrue();
        assertThat(started.data().get("stage")).isEqualTo("BUILDING");
        String datasetId = (String) started.data().get("datasetId");

        ToolResult appendedFirstBatch = tool.execute(new ToolRequest("storeDataset", "APPEND_RECORDS", "conversation-1", "request-1",
                "extraction", "", Map.of(
                        "datasetId", datasetId,
                        "records", List.of(
                                Map.of("network", "Biedronka", "fullAddress", "A 3", "sourceAttachmentId", "att-1", "sourceRow", 3)
                        )
                )));
        assertThat(appendedFirstBatch.success()).isTrue();
        assertThat(appendedFirstBatch.data().get("count")).isEqualTo(3);
        assertThat(appendedFirstBatch.data().get("stage")).isEqualTo("BUILDING");

        // A second APPEND_RECORDS batch must accumulate on top of the first, not replace it -
        // this is the exact incremental shape the model is expected to use for a large extraction.
        ToolResult appended = tool.execute(new ToolRequest("storeDataset", "APPEND_RECORDS", "conversation-1", "request-1",
                "extraction", "", Map.of(
                        "datasetId", datasetId,
                        "records", List.of(
                                Map.of("network", "Biedronka", "fullAddress", "A 4", "sourceAttachmentId", "att-1", "sourceRow", 4)
                        )
                )));
        assertThat(appended.success()).isTrue();
        assertThat(appended.data().get("count")).isEqualTo(4);
        assertThat(appended.data().get("stage")).isEqualTo("BUILDING");

        // A dataset still BUILDING must reject SUBMIT_SCHEDULE.
        ToolResult prematureSchedule = tool.execute(new ToolRequest("storeDataset", "SUBMIT_SCHEDULE", "conversation-1", "request-1",
                "schedule", "", Map.of("datasetId", datasetId, "days", List.of(
                        Map.of("day", 1, "storeIds", List.of("store-001", "store-002", "store-003"))
                ))));
        assertThat(prematureSchedule.success()).isFalse();

        ToolResult finalized = tool.execute(new ToolRequest("storeDataset", "FINALIZE_DATASET", "conversation-1", "request-1",
                "finalize", "", Map.of("datasetId", datasetId)));
        assertThat(finalized.success()).isTrue();
        assertThat(finalized.data().get("stage")).isEqualTo("EXTRACTED");
        assertThat(finalized.data().get("count")).isEqualTo(4);

        // SUBMIT_SCHEDULE requires geolocation first (stage=GEOLOCATED) - advance the dataset for
        // real through VERIFY_DATASET and geolocation before the schedule can be accepted.
        List<String> ids = List.of("store-001", "store-002", "store-003", "store-004");
        List<VerificationEntry> verifications = ids.stream()
                .map(id -> new VerificationEntry(id, "VERIFIED", "", "")).toList();
        assertThat(service.verifyDataset(datasetId, verifications).success()).isTrue();
        List<GeolocationEntry> geolocations = ids.stream()
                .map(id -> new GeolocationEntry(id, GeolocationStatus.RESOLVED, 52.0, 21.0)).toList();
        assertThat(service.updateGeolocation(datasetId, geolocations).success()).isTrue();
        setAnyDayPreferences(tool, datasetId);

        ToolResult schedule = tool.execute(new ToolRequest("storeDataset", "SUBMIT_SCHEDULE", "conversation-1", "request-1",
                "schedule", "", Map.of("datasetId", datasetId, "days", List.of(
                        scheduleDayMap(1, "2026-06-02", List.of("store-001", "store-002", "store-003", "store-004"))
                ))));
        assertThat(schedule.success()).isTrue();
        assertThat(schedule.data().get("stage")).isEqualTo("SCHEDULED");
    }

    @Test
    void appendRecordsOnAnUnknownDatasetIdFailsWithStoreDatasetNotFound() {
        StoreDatasetTool tool = new StoreDatasetTool(new StoreAuditDatasetService(new NoopCognitiveEventBus()));

        ToolResult result = tool.execute(new ToolRequest("storeDataset", "APPEND_RECORDS", "conversation-1", "request-1",
                "extraction", "", Map.of("datasetId", "does-not-exist", "records", List.of(
                        Map.of("network", "Biedronka", "fullAddress", "A 1", "sourceAttachmentId", "att-1", "sourceRow", 1)
                ))));

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("STORE_DATASET_NOT_FOUND");
    }

    @Test
    void createDatasetWithAnEmptyRecordListReturnsEmptyDatasetErrorCodeAndNeverSucceeds() {
        StoreDatasetTool tool = new StoreDatasetTool(new StoreAuditDatasetService(new NoopCognitiveEventBus()));

        ToolResult result = tool.execute(new ToolRequest("storeDataset", "CREATE_DATASET", "conversation-1", "request-1",
                "extraction", "", Map.of(
                        "sourceImageCount", 2,
                        "sourceAttachmentIds", List.of("att-1", "att-2"),
                        "records", List.of()
                )));

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("EMPTY_DATASET");
    }

    @Test
    void verifyDatasetWithWildlyDifferentCountFailsAsInvariantViolationAndDoesNotMutateTheDataset() {
        StoreAuditDatasetService service = new StoreAuditDatasetService(new NoopCognitiveEventBus());
        StoreDatasetTool tool = new StoreDatasetTool(service);

        ToolResult created = tool.execute(new ToolRequest("storeDataset", "CREATE_DATASET", "conversation-1", "request-1",
                "extraction", "", Map.of(
                        "sourceImageCount", 1,
                        "sourceAttachmentIds", List.of("att-1"),
                        "records", List.of(
                                Map.of("fullAddress", "A 1", "sourceAttachmentId", "att-1", "sourceRow", 1),
                                Map.of("fullAddress", "A 2", "sourceAttachmentId", "att-1", "sourceRow", 2),
                                Map.of("fullAddress", "A 3", "sourceAttachmentId", "att-1", "sourceRow", 3)
                        )
                )));
        String datasetId = (String) created.data().get("datasetId");

        List<Map<String, Object>> bogusVerifications = new java.util.ArrayList<>();
        for (int index = 0; index < 40; index++) {
            bogusVerifications.add(Map.of("recordId", "store-" + String.format("%03d", index + 1), "status", "VERIFIED"));
        }
        ToolResult verifyResult = tool.execute(new ToolRequest("storeDataset", "VERIFY_DATASET", "conversation-1", "request-1",
                "verification", "", Map.of("datasetId", datasetId, "verifications", bogusVerifications)));

        assertThat(verifyResult.success()).isFalse();
        assertThat(verifyResult.errorCode()).isEqualTo("STORE_DATASET_INVARIANT_VIOLATION");

        ToolResult getResult = tool.execute(new ToolRequest("storeDataset", "GET_DATASET", "conversation-1", "request-1",
                "check", "", Map.of("datasetId", datasetId)));
        assertThat(getResult.data().get("count")).isEqualTo(3);
    }

    @Test
    void submitScheduleAcceptsAValidGroupingAndRejectsAScheduleMissingAStore() {
        StoreAuditDatasetService service = fixedClockService();
        StoreDatasetTool tool = new StoreDatasetTool(service);

        ToolResult created = tool.execute(new ToolRequest("storeDataset", "CREATE_DATASET", "conversation-1", "request-1",
                "extraction", "", Map.of(
                        "sourceImageCount", 1,
                        "sourceAttachmentIds", List.of("att-1"),
                        "records", List.of(
                                Map.of("fullAddress", "A 1", "sourceAttachmentId", "att-1", "sourceRow", 1),
                                Map.of("fullAddress", "A 2", "sourceAttachmentId", "att-1", "sourceRow", 2),
                                Map.of("fullAddress", "A 3", "sourceAttachmentId", "att-1", "sourceRow", 3)
                        )
                )));
        String datasetId = (String) created.data().get("datasetId");

        // SUBMIT_SCHEDULE requires geolocation first (stage=GEOLOCATED) - advance the dataset for
        // real through VERIFY_DATASET and geolocation before the schedule can be accepted.
        List<String> ids = List.of("store-001", "store-002", "store-003");
        List<VerificationEntry> verifications = ids.stream()
                .map(id -> new VerificationEntry(id, "VERIFIED", "", "")).toList();
        assertThat(service.verifyDataset(datasetId, verifications).success()).isTrue();
        List<GeolocationEntry> geolocations = ids.stream()
                .map(id -> new GeolocationEntry(id, GeolocationStatus.RESOLVED, 52.0, 21.0)).toList();
        assertThat(service.updateGeolocation(datasetId, geolocations).success()).isTrue();
        setAnyDayPreferences(tool, datasetId);

        ToolResult validSchedule = tool.execute(new ToolRequest("storeDataset", "SUBMIT_SCHEDULE", "conversation-1", "request-1",
                "schedule", "", Map.of("datasetId", datasetId, "days", List.of(
                        scheduleDayMap(1, "2026-06-02", List.of("store-001", "store-002", "store-003"))
                ))));
        assertThat(validSchedule.success()).isTrue();
        assertThat(validSchedule.data().get("stage")).isEqualTo("SCHEDULED");

        ToolResult incompleteSchedule = tool.execute(new ToolRequest("storeDataset", "SUBMIT_SCHEDULE", "conversation-1", "request-1",
                "schedule", "", Map.of("datasetId", datasetId, "days", List.of(
                        scheduleDayMap(1, "2026-06-02", List.of("store-001", "store-002"))
                ))));
        assertThat(incompleteSchedule.success()).isFalse();
        assertThat(incompleteSchedule.errorCode()).isEqualTo("STORE_DATASET_INVARIANT_VIOLATION");
        @SuppressWarnings("unchecked")
        List<String> missing = (List<String>) incompleteSchedule.data().get("missingStoreIds");
        assertThat(missing).containsExactly("store-003");
    }

    // =====================================================================
    // Regression tests for Core-owned recordIndex/storeIndexes mapping and hard stage guards
    // (round 4): the model references records by their simple 1-based position in the canonical
    // dataset instead of having to remember/reconstruct "store-NNN" strings, and Core enforces the
    // Store Audit state machine's order deterministically rather than relying on the model
    // remembering a prose instruction.
    // =====================================================================

    // TEST 1: VERIFY recordIndex mapping - a 3-record dataset, verified via recordIndex 1..3
    // instead of the exact "store-001".."store-003" strings.
    @Test
    void verifyDatasetResolvesRecordIndexToTheCanonicalRecordId() {
        StoreAuditDatasetService service = new StoreAuditDatasetService(new NoopCognitiveEventBus());
        StoreDatasetTool tool = new StoreDatasetTool(service);
        String datasetId = createThreeRecordDataset(tool);

        ToolResult verify = tool.execute(new ToolRequest("storeDataset", "VERIFY_DATASET", "conversation-1", "request-1",
                "verification", "", Map.of("datasetId", datasetId, "verifications", List.of(
                        Map.of("recordIndex", 1, "status", "VERIFIED"),
                        Map.of("recordIndex", 2, "status", "VERIFIED"),
                        Map.of("recordIndex", 3, "status", "VERIFIED")))));

        assertThat(verify.success()).isTrue();
        assertThat(verify.data().get("stage")).isEqualTo("LOCKED");
    }

    // TEST 2: 23-record VERIFY via recordIndex 1..23 - full coverage, no missing/unknown/duplicate,
    // stage EXTRACTED -> LOCKED.
    @Test
    void verifyDatasetAcceptsATwentyThreeRecordPassEntirelyByRecordIndex() {
        StoreAuditDatasetService service = new StoreAuditDatasetService(new NoopCognitiveEventBus());
        StoreDatasetTool tool = new StoreDatasetTool(service);
        String datasetId = createDatasetWithRecordCount(tool, 23);

        List<Map<String, Object>> verifications = new java.util.ArrayList<>();
        for (int index = 1; index <= 23; index++) {
            verifications.add(Map.of("recordIndex", index, "status", "VERIFIED"));
        }
        ToolResult verify = tool.execute(new ToolRequest("storeDataset", "VERIFY_DATASET", "conversation-1", "request-1",
                "verification", "", Map.of("datasetId", datasetId, "verifications", verifications)));

        assertThat(verify.success()).isTrue();
        assertThat(verify.data().get("stage")).isEqualTo("LOCKED");
        assertThat(verify.data().get("count")).isEqualTo(23);
    }

    // TEST 3: out-of-range recordIndex rejects the WHOLE call outright - never guessed or clamped,
    // dataset stays EXTRACTED.
    @Test
    void verifyDatasetRejectsAnOutOfRangeRecordIndex() {
        StoreAuditDatasetService service = new StoreAuditDatasetService(new NoopCognitiveEventBus());
        StoreDatasetTool tool = new StoreDatasetTool(service);
        String datasetId = createDatasetWithRecordCount(tool, 23);

        ToolResult verify = tool.execute(new ToolRequest("storeDataset", "VERIFY_DATASET", "conversation-1", "request-1",
                "verification", "", Map.of("datasetId", datasetId, "verifications", List.of(
                        Map.of("recordIndex", 24, "status", "VERIFIED")))));

        assertThat(verify.success()).isFalse();
        assertThat(verify.errorCode()).isEqualTo("STORE_RECORD_INDEX_OUT_OF_RANGE");
        assertThat(verify.data().get("recordCount")).isEqualTo(23);

        ToolResult get = tool.execute(new ToolRequest("storeDataset", "GET_DATASET", "conversation-1", "request-1",
                "check", "", Map.of("datasetId", datasetId)));
        assertThat(get.data().get("stage")).isEqualTo("EXTRACTED");
    }

    // TEST 4: the same recordIndex used twice - rejected per the existing full-coverage invariant
    // (missing the other records, that one duplicated).
    @Test
    void verifyDatasetRejectsADuplicateRecordIndex() {
        StoreAuditDatasetService service = new StoreAuditDatasetService(new NoopCognitiveEventBus());
        StoreDatasetTool tool = new StoreDatasetTool(service);
        String datasetId = createThreeRecordDataset(tool);

        ToolResult verify = tool.execute(new ToolRequest("storeDataset", "VERIFY_DATASET", "conversation-1", "request-1",
                "verification", "", Map.of("datasetId", datasetId, "verifications", List.of(
                        Map.of("recordIndex", 1, "status", "VERIFIED"),
                        Map.of("recordIndex", 1, "status", "VERIFIED"),
                        Map.of("recordIndex", 2, "status", "VERIFIED")))));

        assertThat(verify.success()).isFalse();
        assertThat(verify.errorCode()).isEqualTo("STORE_DATASET_INVARIANT_VIOLATION");
        @SuppressWarnings("unchecked")
        List<String> duplicates = (List<String>) verify.data().get("duplicateRecordIds");
        assertThat(duplicates).containsExactly("store-001");
        @SuppressWarnings("unchecked")
        List<String> missing = (List<String>) verify.data().get("missingRecordIds");
        assertThat(missing).containsExactly("store-003");
    }

    // TEST 5: SUBMIT_SCHEDULE storeIndexes mapping - a 5-record dataset, scheduled via storeIndexes
    // instead of the exact "store-001".."store-005" strings.
    @Test
    void submitScheduleResolvesStoreIndexesToTheCanonicalRecordIds() {
        StoreAuditDatasetService service = fixedClockService();
        StoreDatasetTool tool = new StoreDatasetTool(service);
        String datasetId = createDatasetWithRecordCount(tool, 5);
        verifyAllByIndex(tool, datasetId, 5);
        geolocateAll(service, datasetId);
        setAnyDayPreferences(tool, datasetId);

        ToolResult schedule = tool.execute(new ToolRequest("storeDataset", "SUBMIT_SCHEDULE", "conversation-1", "request-1",
                "schedule", "", Map.of("datasetId", datasetId, "days", List.of(
                        scheduleDayMapByIndex(1, "2026-06-02", List.of(1, 2, 3)),
                        scheduleDayMapByIndex(2, "2026-06-03", List.of(4, 5))))));

        assertThat(schedule.success()).isTrue();
        assertThat(schedule.data().get("stage")).isEqualTo("SCHEDULED");
    }

    // TEST 6: an out-of-range storeIndex is rejected deterministically - never guessed or clamped.
    @Test
    void submitScheduleRejectsAnOutOfRangeStoreIndex() {
        StoreAuditDatasetService service = new StoreAuditDatasetService(new NoopCognitiveEventBus());
        StoreDatasetTool tool = new StoreDatasetTool(service);
        String datasetId = createDatasetWithRecordCount(tool, 23);
        verifyAllByIndex(tool, datasetId, 23);
        geolocateAll(service, datasetId);

        ToolResult schedule = tool.execute(new ToolRequest("storeDataset", "SUBMIT_SCHEDULE", "conversation-1", "request-1",
                "schedule", "", Map.of("datasetId", datasetId, "days", List.of(
                        Map.of("day", 1, "storeIndexes", List.of(1, 99))))));

        assertThat(schedule.success()).isFalse();
        assertThat(schedule.errorCode()).isEqualTo("STORE_RECORD_INDEX_OUT_OF_RANGE");
    }

    // TEST 7: EXTRACTED -> GEOCODE_DATASET is a genuinely invalid stage transition; asserted at the
    // LocationTool boundary (StoreDatasetTool has no GEOCODE_DATASET operation).
    @Test
    void geocodeDatasetRejectsAnExtractedDatasetImmediately() {
        StoreAuditDatasetService service = new StoreAuditDatasetService(new NoopCognitiveEventBus());
        StoreDatasetTool tool = new StoreDatasetTool(service);
        String datasetId = createThreeRecordDataset(tool);
        com.jarvis.tools.location.LocationTool locationTool = new com.jarvis.tools.location.LocationTool(
                query -> { throw new AssertionError("GeocodingClient must never be invoked on an EXTRACTED dataset"); },
                new com.jarvis.tools.location.RoutingClient() {
                    @Override
                    public com.jarvis.tools.location.RouteResult route(com.jarvis.tools.location.GeoPoint from, com.jarvis.tools.location.GeoPoint to) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public com.jarvis.tools.location.RouteMatrixResult table(List<com.jarvis.tools.location.GeoPoint> points) {
                        throw new UnsupportedOperationException();
                    }
                },
                new com.jarvis.tools.location.LocationProperties(true, "https://nominatim.example", "https://osrm.example",
                        "Test-Agent/1.0", 0, 25, 8, java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(1), 5),
                service);

        ToolResult geocode = locationTool.execute(new ToolRequest("location", "GEOCODE_DATASET", "conversation-1", "request-1",
                "geo", "", Map.of("datasetId", datasetId)));

        assertThat(geocode.success()).isFalse();
        assertThat(geocode.errorCode()).isEqualTo("STORE_DATASET_NOT_VERIFIED");
    }

    // TEST 8: EXTRACTED -> SUBMIT_SCHEDULE is a genuinely invalid stage transition - rejected
    // immediately as STORE_AUDIT_INVALID_STAGE, never reaching the missing/duplicate/unknown
    // invariant check.
    @Test
    void submitScheduleRejectsAnExtractedDatasetImmediately() {
        StoreAuditDatasetService service = new StoreAuditDatasetService(new NoopCognitiveEventBus());
        StoreDatasetTool tool = new StoreDatasetTool(service);
        String datasetId = createThreeRecordDataset(tool);

        ToolResult schedule = tool.execute(new ToolRequest("storeDataset", "SUBMIT_SCHEDULE", "conversation-1", "request-1",
                "schedule", "", Map.of("datasetId", datasetId, "days", List.of(
                        Map.of("day", 1, "storeIndexes", List.of(1, 2, 3))))));

        assertThat(schedule.success()).isFalse();
        assertThat(schedule.errorCode()).isEqualTo("STORE_AUDIT_INVALID_STAGE");
        assertThat(schedule.data().get("stage")).isEqualTo("EXTRACTED");
        assertThat(schedule.data().get("requiredNextAction")).isEqualTo("VERIFY_DATASET");
    }

    // TEST: LOCKED -> SUBMIT_SCHEDULE (geolocation skipped) is also rejected - never allowed just
    // because the record-id coverage invariant happens to be satisfiable.
    @Test
    void submitScheduleRejectsALockedDatasetThatWasNeverGeolocated() {
        StoreAuditDatasetService service = new StoreAuditDatasetService(new NoopCognitiveEventBus());
        StoreDatasetTool tool = new StoreDatasetTool(service);
        String datasetId = createThreeRecordDataset(tool);
        verifyAllByIndex(tool, datasetId, 3);

        ToolResult schedule = tool.execute(new ToolRequest("storeDataset", "SUBMIT_SCHEDULE", "conversation-1", "request-1",
                "schedule", "", Map.of("datasetId", datasetId, "days", List.of(
                        Map.of("day", 1, "storeIndexes", List.of(1, 2, 3))))));

        assertThat(schedule.success()).isFalse();
        assertThat(schedule.errorCode()).isEqualTo("STORE_AUDIT_INVALID_STAGE");
        assertThat(schedule.data().get("stage")).isEqualTo("LOCKED");
    }

    private String createThreeRecordDataset(StoreDatasetTool tool) {
        return createDatasetWithRecordCount(tool, 3);
    }

    private String createDatasetWithRecordCount(StoreDatasetTool tool, int count) {
        List<Map<String, Object>> records = new java.util.ArrayList<>();
        for (int row = 1; row <= count; row++) {
            records.add(Map.of("network", "Biedronka", "fullAddress", "Adres " + row,
                    "sourceAttachmentId", "att-1", "sourceRow", row));
        }
        ToolResult created = tool.execute(new ToolRequest("storeDataset", "CREATE_DATASET", "conversation-1", "request-1",
                "extraction", "", Map.of(
                        "sourceImageCount", 1,
                        "sourceAttachmentIds", List.of("att-1"),
                        "records", records
                )));
        assertThat(created.success()).isTrue();
        return (String) created.data().get("datasetId");
    }

    private void verifyAllByIndex(StoreDatasetTool tool, String datasetId, int count) {
        List<Map<String, Object>> verifications = new java.util.ArrayList<>();
        for (int index = 1; index <= count; index++) {
            verifications.add(Map.of("recordIndex", index, "status", "VERIFIED"));
        }
        ToolResult verify = tool.execute(new ToolRequest("storeDataset", "VERIFY_DATASET", "conversation-1", "request-1",
                "verification", "", Map.of("datasetId", datasetId, "verifications", verifications)));
        assertThat(verify.success()).isTrue();
    }

    private void geolocateAll(StoreAuditDatasetService service, String datasetId) {
        StoreAuditDataset dataset = service.getDataset(datasetId).orElseThrow();
        List<GeolocationEntry> entries = dataset.stores().stream()
                .map(record -> new GeolocationEntry(record.id(), GeolocationStatus.RESOLVED, 52.0, 21.0)).toList();
        assertThat(service.updateGeolocation(datasetId, entries).success()).isTrue();
    }

    // Fixed "today" (a Monday) so SUBMIT_SCHEDULE date validation tests are fully deterministic,
    // never dependent on the machine's real clock.
    private static final Instant FIXED_NOW = Instant.parse("2026-06-01T10:00:00Z");

    private StoreAuditDatasetService fixedClockService() {
        return new StoreAuditDatasetService(new NoopCognitiveEventBus(), Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
    }

    private void setAnyDayPreferences(StoreDatasetTool tool, String datasetId) {
        ToolResult result = tool.execute(new ToolRequest("storeDataset", "SET_PREFERENCES", "conversation-1", "request-1",
                "preferences", "", Map.of("datasetId", datasetId, "year", 2026, "month", 6,
                        "preferredDaysOfWeek", List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"))));
        assertThat(result.success()).isTrue();
    }

    private Map<String, Object> scheduleDayMap(int day, String date, List<String> storeIds) {
        return Map.of("day", day, "date", date, "storeIds", storeIds,
                "routeDistanceMeters", 1000d, "routeDurationSeconds", 600d, "auditDurationSeconds", 300d);
    }

    private Map<String, Object> scheduleDayMapByIndex(int day, String date, List<Integer> storeIndexes) {
        return Map.of("day", day, "date", date, "storeIndexes", storeIndexes,
                "routeDistanceMeters", 1000d, "routeDurationSeconds", 600d, "auditDurationSeconds", 300d);
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
