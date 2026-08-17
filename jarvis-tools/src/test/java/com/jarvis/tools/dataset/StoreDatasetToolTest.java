package com.jarvis.tools.dataset;

import com.jarvis.common.ai.BrainType;
import com.jarvis.common.event.CognitiveEvent;
import com.jarvis.common.event.CognitiveEventBus;
import com.jarvis.common.event.CognitiveEventType;
import com.jarvis.tools.ToolRequest;
import com.jarvis.tools.ToolResult;
import org.junit.jupiter.api.Test;

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
        StoreDatasetTool tool = new StoreDatasetTool(new StoreAuditDatasetService(new NoopCognitiveEventBus()));

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

        ToolResult appended = tool.execute(new ToolRequest("storeDataset", "APPEND_RECORDS", "conversation-1", "request-1",
                "extraction", "", Map.of(
                        "datasetId", datasetId,
                        "records", List.of(
                                Map.of("network", "Biedronka", "fullAddress", "A 3", "sourceAttachmentId", "att-1", "sourceRow", 3)
                        )
                )));
        assertThat(appended.success()).isTrue();
        assertThat(appended.data().get("count")).isEqualTo(3);
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
        assertThat(finalized.data().get("count")).isEqualTo(3);

        ToolResult schedule = tool.execute(new ToolRequest("storeDataset", "SUBMIT_SCHEDULE", "conversation-1", "request-1",
                "schedule", "", Map.of("datasetId", datasetId, "days", List.of(
                        Map.of("day", 1, "storeIds", List.of("store-001", "store-002", "store-003"))
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

        ToolResult validSchedule = tool.execute(new ToolRequest("storeDataset", "SUBMIT_SCHEDULE", "conversation-1", "request-1",
                "schedule", "", Map.of("datasetId", datasetId, "days", List.of(
                        Map.of("day", 1, "storeIds", List.of("store-001", "store-002", "store-003"))
                ))));
        assertThat(validSchedule.success()).isTrue();
        assertThat(validSchedule.data().get("stage")).isEqualTo("SCHEDULED");

        ToolResult incompleteSchedule = tool.execute(new ToolRequest("storeDataset", "SUBMIT_SCHEDULE", "conversation-1", "request-1",
                "schedule", "", Map.of("datasetId", datasetId, "days", List.of(
                        Map.of("day", 1, "storeIds", List.of("store-001", "store-002"))
                ))));
        assertThat(incompleteSchedule.success()).isFalse();
        assertThat(incompleteSchedule.errorCode()).isEqualTo("STORE_DATASET_INVARIANT_VIOLATION");
        @SuppressWarnings("unchecked")
        List<String> missing = (List<String>) incompleteSchedule.data().get("missingStoreIds");
        assertThat(missing).containsExactly("store-003");
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
