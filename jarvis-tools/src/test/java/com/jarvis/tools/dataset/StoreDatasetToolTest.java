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
