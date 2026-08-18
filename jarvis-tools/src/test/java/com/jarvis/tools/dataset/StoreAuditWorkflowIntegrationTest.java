package com.jarvis.tools.dataset;

import com.jarvis.common.ai.BrainType;
import com.jarvis.common.event.CognitiveEvent;
import com.jarvis.common.event.CognitiveEventBus;
import com.jarvis.common.event.CognitiveEventType;
import com.jarvis.tools.ToolRequest;
import com.jarvis.tools.ToolResult;
import com.jarvis.tools.location.GeocodeResult;
import com.jarvis.tools.location.GeocodingClient;
import com.jarvis.tools.location.LocationProperties;
import com.jarvis.tools.location.LocationTool;
import com.jarvis.tools.location.RouteMatrixResult;
import com.jarvis.tools.location.RouteResult;
import com.jarvis.tools.location.RoutingClient;
import com.jarvis.tools.location.GeoPoint;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REGRESSION TEST 11 - the exact production-style Store Audit workflow, end to end through the
 * real tools (never simplified/bypassed), proving the fixed state machine actually reaches
 * SCHEDULED with every source record intact: 23 store records split across 2 real current-message
 * attachments (14 from one, 9 from the other - purely an illustrative regression fixture, matching
 * the production bug report's shape; nothing about "14", "9", "Biedronka", or "Stokrotka" is
 * hardcoded in production logic, only in this test's fixture data) go through
 * START_DATASET -&gt; APPEND_RECORDS -&gt; FINALIZE_DATASET -&gt; VERIFY_DATASET -&gt;
 * (workflow document read, gated at the NativeToolLoopService layer - see
 * NativeToolLoopServiceCompletionGateTest) -&gt; GEOCODE_DATASET -&gt; SUBMIT_SCHEDULE, and the
 * canonical dataset still has all 23 records at every step - it must never silently shrink to 14.
 */
class StoreAuditWorkflowIntegrationTest {

    private static final LocationProperties LOCATION_PROPERTIES = new LocationProperties(
            true, "https://nominatim.example", "https://osrm.example", "Test-Agent/1.0",
            0, 25, 8, Duration.ofSeconds(1), Duration.ofSeconds(1), 5);

    @Test
    void fullProductionStyleWorkflowReachesScheduledWithAllTwentyThreeRecordsIntact() {
        StoreAuditDatasetService datasetService = new StoreAuditDatasetService(new NoopCognitiveEventBus());
        StoreDatasetTool storeDatasetTool = new StoreDatasetTool(datasetService);
        LocationTool locationTool = new LocationTool(new AlwaysResolvingGeocodingClient(), new UnusedRoutingClient(), LOCATION_PROPERTIES, datasetService);

        // Core already knows the real current-message attachments (2 screenshots) before any tool
        // call - the model only needs to reference them, never re-declare the complete set itself.
        datasetService.registerAttachments("request-1", "conversation-1", List.of("image-A", "image-B"));

        // START_DATASET: first batch (14 records) from image-A, declaring the total it expects
        // across every batch (23) up front.
        ToolResult started = storeDatasetTool.execute(new ToolRequest("storeDataset", "START_DATASET", "conversation-1", "request-1",
                "extraction", "", Map.of(
                        "sourceImageCount", 2,
                        "expectedRecordCount", 23,
                        "sourceAttachmentIds", List.of("image-A"),
                        "records", recordMaps("Biedronka", "image-A", 1, 14)
                )));
        assertThat(started.success()).isTrue();
        String datasetId = (String) started.data().get("datasetId");
        assertThat(started.data().get("count")).isEqualTo(14);

        // APPEND_RECORDS: second batch (9 records) from image-B - accepted even though image-B was
        // never declared in the original START_DATASET call, because Core's real registered
        // attachment set (both image-A and image-B) governs provenance, not that declaration.
        ToolResult appended = storeDatasetTool.execute(new ToolRequest("storeDataset", "APPEND_RECORDS", "conversation-1", "request-1",
                "extraction", "", Map.of(
                        "datasetId", datasetId,
                        "records", recordMaps("Stokrotka", "image-B", 15, 23)
                )));
        assertThat(appended.success()).isTrue();
        assertThat(appended.data().get("count")).isEqualTo(23);

        // FINALIZE_DATASET: accepted count (23) matches the declared expectedRecordCount (23).
        ToolResult finalized = storeDatasetTool.execute(new ToolRequest("storeDataset", "FINALIZE_DATASET", "conversation-1", "request-1",
                "finalize", "", Map.of("datasetId", datasetId)));
        assertThat(finalized.success()).isTrue();
        assertThat(finalized.data().get("stage")).isEqualTo("EXTRACTED");
        assertThat(finalized.data().get("count")).isEqualTo(23);

        // GEOCODE_DATASET must be rejected before verification - proves the state machine gate is
        // live in this exact call chain, not just at the service layer.
        ToolResult prematureGeocode = locationTool.execute(new ToolRequest("location", "GEOCODE_DATASET", "conversation-1", "request-1", "", "",
                Map.of("datasetId", datasetId)));
        assertThat(prematureGeocode.success()).isFalse();
        assertThat(prematureGeocode.errorCode()).isEqualTo("STORE_DATASET_NOT_VERIFIED");

        // VERIFY_DATASET: full 23/23 pass, every canonical id exactly once.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> currentRecords = (List<Map<String, Object>>) finalized.data().get("records");
        List<Map<String, Object>> verifications = currentRecords.stream()
                .map(record -> Map.<String, Object>of("recordId", record.get("id"), "status", "VERIFIED"))
                .toList();
        ToolResult verified = storeDatasetTool.execute(new ToolRequest("storeDataset", "VERIFY_DATASET", "conversation-1", "request-1",
                "verification", "", Map.of("datasetId", datasetId, "verifications", verifications)));
        assertThat(verified.success()).isTrue();
        assertThat(verified.data().get("stage")).isEqualTo("LOCKED");
        assertThat(verified.data().get("count")).isEqualTo(23);

        // GEOCODE_DATASET: canonical API - only datasetId, Core reads id/fullAddress from the
        // locked dataset itself for every one of the 23 records.
        ToolResult geocoded = locationTool.execute(new ToolRequest("location", "GEOCODE_DATASET", "conversation-1", "request-1", "", "",
                Map.of("datasetId", datasetId)));
        assertThat(geocoded.success()).isTrue();
        assertThat(geocoded.data().get("updatedCount")).isEqualTo(23);
        assertThat(geocoded.data().get("datasetStage")).isEqualTo("GEOLOCATED");
        assertThat(geocoded.data().get("datasetCount")).isEqualTo(23);

        // SUBMIT_SCHEDULE: every one of the 23 canonical ids, exactly once, across 2 days.
        List<String> recordIds = currentRecords.stream().map(record -> (String) record.get("id")).toList();
        ToolResult scheduled = storeDatasetTool.execute(new ToolRequest("storeDataset", "SUBMIT_SCHEDULE", "conversation-1", "request-1",
                "schedule", "", Map.of("datasetId", datasetId, "days", List.of(
                        Map.of("day", 1, "storeIds", recordIds.subList(0, 12)),
                        Map.of("day", 2, "storeIds", recordIds.subList(12, 23))
                ))));

        assertThat(scheduled.success()).isTrue();
        assertThat(scheduled.data().get("stage")).isEqualTo("SCHEDULED");
        assertThat(scheduled.data().get("count")).isEqualTo(23);

        // Final invariant: the canonical dataset still has all 23 records - it never silently
        // shrunk to 14 (the exact production bug) at any point across the whole chain.
        StoreAuditDataset finalDataset = datasetService.getDataset(datasetId).orElseThrow();
        assertThat(finalDataset.stage()).isEqualTo(DatasetStage.SCHEDULED);
        assertThat(finalDataset.stores()).hasSize(23);
        assertThat(finalDataset.schedule()).hasSize(2);
        long scheduledUniqueStores = finalDataset.schedule().stream().flatMap(day -> day.storeIds().stream()).distinct().count();
        assertThat(scheduledUniqueStores).isEqualTo(23);
    }

    private List<Map<String, Object>> recordMaps(String network, String attachmentId, int fromRow, int toRowInclusive) {
        List<Map<String, Object>> records = new ArrayList<>();
        for (int row = fromRow; row <= toRowInclusive; row++) {
            Map<String, Object> record = new HashMap<>();
            record.put("network", network);
            record.put("city", "Miasto Testowe");
            record.put("street", "Ulica Testowa");
            record.put("buildingNumber", String.valueOf(row));
            record.put("postalCode", "00-0" + String.format("%02d", row % 100));
            record.put("fullAddress", "Ulica Testowa " + row + ", Miasto Testowe");
            record.put("sourceAttachmentId", attachmentId);
            record.put("sourceRow", row);
            records.add(record);
        }
        return records;
    }

    /**
     * Resolves any address deterministically - the exact coordinate values don't matter for this
     * test, only that every one of the 23 canonical addresses geocodes successfully.
     */
    private static final class AlwaysResolvingGeocodingClient implements GeocodingClient {

        @Override
        public GeocodeResult geocode(String query) {
            double latitude = 50.0 + (Math.abs(query.hashCode()) % 1000) / 1000d;
            double longitude = 20.0 + (Math.abs(query.hashCode()) % 500) / 1000d;
            return GeocodeResult.resolved(query, latitude, longitude, query);
        }
    }

    private static final class UnusedRoutingClient implements RoutingClient {

        @Override
        public RouteResult route(GeoPoint from, GeoPoint to) {
            throw new UnsupportedOperationException("Not used in this test");
        }

        @Override
        public RouteMatrixResult table(List<GeoPoint> points) {
            throw new UnsupportedOperationException("Not used in this test");
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
