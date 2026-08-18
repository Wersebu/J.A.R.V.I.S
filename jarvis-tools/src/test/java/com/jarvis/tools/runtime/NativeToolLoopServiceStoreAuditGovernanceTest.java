package com.jarvis.tools.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.common.ai.AIJobType;
import com.jarvis.common.ai.AIProvider;
import com.jarvis.common.ai.Brain;
import com.jarvis.common.ai.BrainType;
import com.jarvis.common.ai.ModelMessage;
import com.jarvis.common.ai.ModelResponse;
import com.jarvis.common.ai.ModelToolCall;
import com.jarvis.common.ai.ModelUsage;
import com.jarvis.common.ai.NativeToolDefinition;
import com.jarvis.common.ai.ReasoningLevel;
import com.jarvis.common.dto.ChatResponse;
import com.jarvis.common.event.ChatEventSink;
import com.jarvis.common.event.CognitiveEvent;
import com.jarvis.common.event.CognitiveEventBus;
import com.jarvis.common.event.CognitiveEventType;
import com.jarvis.common.knowledge.KnowledgeMode;
import com.jarvis.tools.JarvisTool;
import com.jarvis.tools.ToolManager;
import com.jarvis.tools.ToolRequest;
import com.jarvis.tools.ToolResult;
import com.jarvis.tools.ToolRuntimeProperties;
import com.jarvis.tools.dataset.DatasetStage;
import com.jarvis.tools.dataset.StoreAuditDataset;
import com.jarvis.tools.dataset.StoreAuditDatasetService;
import com.jarvis.tools.dataset.StoreDatasetTool;
import com.jarvis.tools.location.GeoPoint;
import com.jarvis.tools.location.GeocodeResult;
import com.jarvis.tools.location.GeocodingClient;
import com.jarvis.tools.location.LocationProperties;
import com.jarvis.tools.location.LocationTool;
import com.jarvis.tools.location.RouteMatrixResult;
import com.jarvis.tools.location.RouteResult;
import com.jarvis.tools.location.RoutingClient;
import com.jarvis.tools.schema.ToolArgumentDefinition;
import com.jarvis.tools.schema.ToolDefinition;
import com.jarvis.tools.schema.ToolOperationDefinition;
import com.jarvis.tools.schema.ToolRegistry;
import com.jarvis.tools.schema.ToolSafetyLevel;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for round-4 Store Audit workflow governance: the required-workflow-document
 * hard gate before {@code GEOCODE_DATASET}, the state-aware {@code GET_DATASET} no-progress guard,
 * and the bounded completion-recovery budget extension - plus one full end-to-end regression
 * covering the exact reported production scenario (23 records, one dataset-identity hiccup along
 * the way, through to a submitted schedule).
 */
class NativeToolLoopServiceStoreAuditGovernanceTest {

    private static final String WORKFLOW_DOCUMENT_PATH = "Work/Scheduling/StoreAuditScheduleWorkflow.md";
    private static final LocationProperties LOCATION_PROPERTIES = new LocationProperties(
            true, "https://nominatim.example", "https://osrm.example", "Test-Agent/1.0",
            0, 25, 8, Duration.ofSeconds(1), Duration.ofSeconds(1), 5);

    // TEST 10/11/12: a LOCKED dataset with the required workflow document NOT yet read blocks
    // GEOCODE_DATASET immediately (never reaching the real LocationTool/geocoding provider);
    // reading the document unlocks it, and geocoding then succeeds normally.
    @Test
    void geocodeIsBlockedUntilTheRequiredWorkflowDocumentIsReadThenSucceeds() {
        StoreAuditDatasetService datasetService = new StoreAuditDatasetService(new NoopCognitiveEventBus());
        datasetService.registerAttachments("request-1", "conversation-1", List.of());
        StoreDatasetTool storeDatasetTool = new StoreDatasetTool(datasetService);
        LocationTool locationTool = new LocationTool(new AlwaysResolvingGeocodingClient(), new UnusedRoutingClient(), LOCATION_PROPERTIES, datasetService);
        FullToolManager toolManager = new FullToolManager(datasetService, storeDatasetTool, locationTool);

        Deque<ModelResponse> turns = new ArrayDeque<>();
        turns.add(toolCallTurn("storedataset__create_dataset", Map.of(
                "sourceImageCount", 1, "expectedRecordCount", 3, "records", records(1, 3))));
        turns.add(toolCallTurn("storedataset__verify_dataset", Map.of("verifications", List.of(
                Map.of("recordIndex", 1, "status", "VERIFIED"),
                Map.of("recordIndex", 2, "status", "VERIFIED"),
                Map.of("recordIndex", 3, "status", "VERIFIED")))));
        // Attempts geocoding BEFORE reading the required document - must be blocked immediately.
        turns.add(toolCallTurn("location__geocode_dataset", Map.of()));
        turns.add(toolCallTurn("knowledge__read_document", Map.of("path", WORKFLOW_DOCUMENT_PATH)));
        turns.add(toolCallTurn("location__geocode_dataset", Map.of()));
        turns.add(textTurn("Geolokalizacja zakonczona."));
        ScriptedProvider provider = new ScriptedProvider(turns);

        NativeToolLoopService service = service(provider, toolManager, datasetService);
        ToolCallingResult result = service.execute(request("request-1", "conversation-1"));

        assertThat(result.handled()).isTrue();
        ToolResult blocked = result.results().stream()
                .filter(toolResult -> "STORE_AUDIT_WORKFLOW_DOCUMENT_NOT_LOADED".equals(toolResult.errorCode()))
                .findFirst().orElseThrow();
        assertThat(blocked.success()).isFalse();
        assertThat(blocked.data().get("stage")).isEqualTo("LOCKED");

        StoreAuditDataset dataset = datasetService.findLatestForConversation("conversation-1").orElseThrow();
        assertThat(dataset.stage()).isEqualTo(DatasetStage.GEOLOCATED);
    }

    // TEST 13: a repeated GET_DATASET against an UNCHANGED dataset is short-circuited into a
    // compact no-progress result instead of dumping the full dataset again.
    @Test
    void repeatedGetDatasetOnAnUnchangedDatasetIsShortCircuited() {
        StoreAuditDatasetService datasetService = new StoreAuditDatasetService(new NoopCognitiveEventBus());
        datasetService.registerAttachments("request-1", "conversation-1", List.of());
        StoreDatasetTool storeDatasetTool = new StoreDatasetTool(datasetService);
        FullToolManager toolManager = new FullToolManager(datasetService, storeDatasetTool, null);

        Deque<ModelResponse> turns = new ArrayDeque<>();
        turns.add(toolCallTurn("storedataset__create_dataset", Map.of(
                "sourceImageCount", 1, "expectedRecordCount", 3, "records", records(1, 3))));
        turns.add(toolCallTurn("storedataset__get_dataset", Map.of()));
        turns.add(toolCallTurn("storedataset__get_dataset", Map.of()));
        turns.add(textTurn("Sprawdzono zestaw danych."));
        ScriptedProvider provider = new ScriptedProvider(turns);

        NativeToolLoopService service = service(provider, toolManager, datasetService);
        ToolCallingResult result = service.execute(request("request-1", "conversation-1"));

        assertThat(result.handled()).isTrue();
        assertThat(result.results()).anyMatch(toolResult -> "STORE_DATASET_GET_NO_PROGRESS".equals(toolResult.errorCode()));
    }

    // TEST 14: GET_DATASET after a genuine mutation (a successful APPEND_RECORDS) must NOT be
    // blocked - the no-progress guard is state-aware, never "GET_DATASET allowed only once".
    @Test
    void getDatasetAfterARealMutationIsNeverBlocked() {
        StoreAuditDatasetService datasetService = new StoreAuditDatasetService(new NoopCognitiveEventBus());
        datasetService.registerAttachments("request-1", "conversation-1", List.of());
        StoreDatasetTool storeDatasetTool = new StoreDatasetTool(datasetService);
        FullToolManager toolManager = new FullToolManager(datasetService, storeDatasetTool, null);

        Deque<ModelResponse> turns = new ArrayDeque<>();
        turns.add(toolCallTurn("storedataset__start_dataset", Map.of(
                "sourceImageCount", 1, "expectedRecordCount", 4, "records", records(1, 3))));
        turns.add(toolCallTurn("storedataset__get_dataset", Map.of()));
        turns.add(toolCallTurn("storedataset__append_records", Map.of("records", records(4, 4))));
        // The dataset genuinely changed (3 -> 4 records) since the last GET_DATASET - this call
        // must be allowed through to the real tool, not blocked as no-progress.
        turns.add(toolCallTurn("storedataset__get_dataset", Map.of()));
        turns.add(textTurn("Zestaw danych ma teraz 4 rekordy."));
        ScriptedProvider provider = new ScriptedProvider(turns);

        NativeToolLoopService service = service(provider, toolManager, datasetService);
        ToolCallingResult result = service.execute(request("request-1", "conversation-1"));

        assertThat(result.handled()).isTrue();
        assertThat(result.results()).noneMatch(toolResult -> "STORE_DATASET_GET_NO_PROGRESS".equals(toolResult.errorCode()));
        long realGetCount = result.results().stream()
                .filter(toolResult -> "storedataset".equalsIgnoreCase(toolResult.tool()) && "GET_DATASET".equalsIgnoreCase(toolResult.operation()))
                .count();
        assertThat(realGetCount).isEqualTo(2);
    }

    // TEST 15: the normal tool-call budget is exhausted exactly when the completion gate wants to
    // re-enter for a genuinely incomplete workflow - a bounded recovery turn must actually run
    // (not "REENTER_TOOL_LOOP" immediately followed by loop termination).
    @Test
    void completionGateRecoveryTurnActuallyRunsWhenTheNormalBudgetIsExhausted() {
        StoreAuditDatasetService datasetService = new StoreAuditDatasetService(new NoopCognitiveEventBus());
        datasetService.registerAttachments("request-1", "conversation-1", List.of());
        StoreDatasetTool storeDatasetTool = new StoreDatasetTool(datasetService);
        FullToolManager toolManager = new FullToolManager(datasetService, storeDatasetTool, null);

        Deque<ModelResponse> turns = new ArrayDeque<>();
        turns.add(toolCallTurn("storedataset__create_dataset", Map.of(
                "sourceImageCount", 1, "expectedRecordCount", 3, "records", records(1, 3))));
        // The model presents this as done while the dataset is only EXTRACTED (never verified) -
        // exactly one call was made, so the very next (and only remaining) turn is the
        // premature-final-answer turn itself, right at the edge of a tiny normal budget.
        turns.add(textTurn("Oto gotowy grafik."));
        // The bonus recovery turn genuinely fixes the workflow.
        turns.add(toolCallTurn("storedataset__verify_dataset", Map.of("verifications", List.of(
                Map.of("recordIndex", 1, "status", "VERIFIED"),
                Map.of("recordIndex", 2, "status", "VERIFIED"),
                Map.of("recordIndex", 3, "status", "VERIFIED")))));
        turns.add(textTurn("Zweryfikowano zestaw danych."));
        ScriptedProvider provider = new ScriptedProvider(turns);

        // A tiny normal budget (2) - the premature final-answer turn lands exactly at the budget
        // edge, reproducing the exact reported bug's shape (REENTER decided with nothing left).
        NativeToolLoopService service = new NativeToolLoopService(
                List.of(provider), toolManager, query -> ToolIntent.NO_TOOL,
                new ToolRuntimeProperties(true, 2, 2, 2, 30, "native", 10, 2),
                new NoopCognitiveEventBus(), new ToolRuntimeDebugService(), new ObjectMapper(),
                new NativeToolSchemaMapper(registry()), datasetService
        );

        ToolCallingResult result = service.execute(request("request-1", "conversation-1"));

        assertThat(result.handled()).isTrue();
        // The recovery turn actually ran and reached VERIFY_DATASET - proof this is not merely a
        // decision to re-enter that never got an actual turn to run in.
        assertThat(provider.callCount()).isGreaterThan(2);
        StoreAuditDataset dataset = datasetService.findLatestForConversation("conversation-1").orElseThrow();
        assertThat(dataset.stage()).isEqualTo(DatasetStage.LOCKED);
    }

    // TEST 16: recovery can carry the workflow all the way to a genuinely finished state.
    @Test
    void recoveryCanReachGenuineCompletion() {
        StoreAuditDatasetService datasetService = new StoreAuditDatasetService(new NoopCognitiveEventBus());
        datasetService.registerAttachments("request-1", "conversation-1", List.of());
        StoreDatasetTool storeDatasetTool = new StoreDatasetTool(datasetService);
        LocationTool locationTool = new LocationTool(new AlwaysResolvingGeocodingClient(), new UnusedRoutingClient(), LOCATION_PROPERTIES, datasetService);
        FullToolManager toolManager = new FullToolManager(datasetService, storeDatasetTool, locationTool);

        Deque<ModelResponse> turns = new ArrayDeque<>();
        turns.add(toolCallTurn("storedataset__create_dataset", Map.of(
                "sourceImageCount", 1, "expectedRecordCount", 3, "records", records(1, 3))));
        turns.add(textTurn("Oto gotowy grafik."));
        turns.add(toolCallTurn("storedataset__verify_dataset", Map.of("verifications", List.of(
                Map.of("recordIndex", 1, "status", "VERIFIED"),
                Map.of("recordIndex", 2, "status", "VERIFIED"),
                Map.of("recordIndex", 3, "status", "VERIFIED")))));
        turns.add(toolCallTurn("knowledge__read_document", Map.of("path", WORKFLOW_DOCUMENT_PATH)));
        turns.add(toolCallTurn("location__geocode_dataset", Map.of()));
        turns.add(submitScheduleTurn());
        turns.add(textTurn("Oto ostateczny grafik na 3 sklepy."));
        ScriptedProvider provider = new ScriptedProvider(turns);

        NativeToolLoopService service = new NativeToolLoopService(
                List.of(provider), toolManager, query -> ToolIntent.NO_TOOL,
                new ToolRuntimeProperties(true, 2, 2, 2, 60, "native", 10, 2),
                new NoopCognitiveEventBus(), new ToolRuntimeDebugService(), new ObjectMapper(),
                new NativeToolSchemaMapper(registry()), datasetService
        );

        ToolCallingResult result = service.execute(request("request-1", "conversation-1"));

        assertThat(result.handled()).isTrue();
        assertThat(result.finalAnswer()).isEqualTo("Oto ostateczny grafik na 3 sklepy.");
        StoreAuditDataset dataset = datasetService.findLatestForConversation("conversation-1").orElseThrow();
        assertThat(dataset.stage()).isEqualTo(DatasetStage.SCHEDULED);
    }

    // TEST 17: a persistently confused model that keeps failing recovery is still bounded - no
    // infinite loop, a normal user-facing failure naming the stage is returned eventually.
    @Test
    void recoveryIsBoundedAndTerminatesWithAnHonestFailureWhenTheModelNeverFixesTheWorkflow() {
        StoreAuditDatasetService datasetService = new StoreAuditDatasetService(new NoopCognitiveEventBus());
        datasetService.registerAttachments("request-1", "conversation-1", List.of());
        StoreDatasetTool storeDatasetTool = new StoreDatasetTool(datasetService);
        FullToolManager toolManager = new FullToolManager(datasetService, storeDatasetTool, null);

        Deque<ModelResponse> turns = new ArrayDeque<>();
        turns.add(toolCallTurn("storedataset__create_dataset", Map.of(
                "sourceImageCount", 1, "expectedRecordCount", 3, "records", records(1, 3))));
        // Every subsequent turn keeps claiming "done" without ever calling VERIFY_DATASET - the
        // ScriptedProvider repeats this same text turn once the deque is exhausted too.
        for (int i = 0; i < 6; i++) {
            turns.add(textTurn("Oto gotowy grafik."));
        }
        ScriptedProvider provider = new ScriptedProvider(turns);

        NativeToolLoopService service = new NativeToolLoopService(
                List.of(provider), toolManager, query -> ToolIntent.NO_TOOL,
                new ToolRuntimeProperties(true, 2, 2, 2, 30, "native", 10, 2),
                new NoopCognitiveEventBus(), new ToolRuntimeDebugService(), new ObjectMapper(),
                new NativeToolSchemaMapper(registry()), datasetService
        );

        ToolCallingResult result = service.execute(request("request-1", "conversation-1"));

        assertThat(result.handled()).isTrue();
        // Bounded: normal budget (2) + a small, capped number of recovery extensions - never
        // anywhere near the 6+ repeated premature-answer turns scripted above.
        assertThat(provider.callCount()).isLessThanOrEqualTo(8);
        StoreAuditDataset dataset = datasetService.findLatestForConversation("conversation-1").orElseThrow();
        assertThat(dataset.stage()).isNotEqualTo(DatasetStage.SCHEDULED);
    }

    // TEST 18: the full real production scenario - 23 records via START/APPEND, a fabricated
    // datasetId rejected mid-workflow (round-3 regression staying fixed), VERIFY by recordIndex,
    // the required workflow document, geolocation, and SUBMIT_SCHEDULE by storeIndexes - reaching
    // SCHEDULED with all 23 records intact.
    @Test
    void fullRealProductionScenarioReachesScheduledWithAllTwentyThreeRecords() {
        StoreAuditDatasetService datasetService = new StoreAuditDatasetService(new NoopCognitiveEventBus());
        datasetService.registerAttachments("request-1", "conversation-1", List.of());
        StoreDatasetTool storeDatasetTool = new StoreDatasetTool(datasetService);
        LocationTool locationTool = new LocationTool(new AlwaysResolvingGeocodingClient(), new UnusedRoutingClient(), LOCATION_PROPERTIES, datasetService);
        FullToolManager toolManager = new FullToolManager(datasetService, storeDatasetTool, locationTool);

        Deque<ModelResponse> turns = new ArrayDeque<>();
        turns.add(toolCallTurn("storedataset__start_dataset", Map.of(
                "sourceImageCount", 2, "expectedRecordCount", 23, "records", records(1, 8))));
        // Hallucinated id - rejected before touching the canonical dataset (round-3 regression).
        turns.add(toolCallTurn("storedataset__append_records", Map.of(
                "datasetId", "dataset_0_0_1724357613666_1724357613666", "records", records(9, 23))));
        turns.add(toolCallTurn("storedataset__append_records", Map.of("records", records(9, 23))));
        turns.add(toolCallTurn("storedataset__finalize_dataset", Map.of()));
        List<Map<String, Object>> verifications = new ArrayList<>();
        for (int index = 1; index <= 23; index++) {
            verifications.add(Map.of("recordIndex", index, "status", "VERIFIED"));
        }
        turns.add(toolCallTurn("storedataset__verify_dataset", Map.of("verifications", verifications)));
        turns.add(toolCallTurn("knowledge__read_document", Map.of("path", WORKFLOW_DOCUMENT_PATH)));
        turns.add(toolCallTurn("location__geocode_dataset", Map.of()));
        turns.add(toolCallTurn("storedataset__submit_schedule", Map.of("days", List.of(
                Map.of("day", 1, "storeIndexes", intRange(1, 12)),
                Map.of("day", 2, "storeIndexes", intRange(13, 23))))));
        turns.add(textTurn("Oto gotowy grafik na 23 sklepy."));
        ScriptedProvider provider = new ScriptedProvider(turns);

        NativeToolLoopService service = new NativeToolLoopService(
                List.of(provider), toolManager, query -> ToolIntent.LOCATION,
                new ToolRuntimeProperties(true, 20, 20, 5, 60, "native", 10, 30),
                new NoopCognitiveEventBus(), new ToolRuntimeDebugService(), new ObjectMapper(),
                new NativeToolSchemaMapper(registry()), datasetService
        );

        ToolCallingResult result = service.execute(request("request-1", "conversation-1"));

        assertThat(result.handled()).isTrue();
        assertThat(result.finalAnswer()).isEqualTo("Oto gotowy grafik na 23 sklepy.");
        StoreAuditDataset dataset = datasetService.findLatestForConversation("conversation-1").orElseThrow();
        assertThat(dataset.stage()).isEqualTo(DatasetStage.SCHEDULED);
        assertThat(dataset.stores()).hasSize(23);
        long scheduledUnique = dataset.schedule().stream().flatMap(day -> day.storeIds().stream()).distinct().count();
        assertThat(scheduledUnique).isEqualTo(23);
    }

    private NativeToolLoopService service(AIProvider provider, ToolManager toolManager, StoreAuditDatasetService datasetService) {
        return new NativeToolLoopService(
                List.of(provider), toolManager, query -> ToolIntent.LOCATION,
                new ToolRuntimeProperties(true, 10, 10, 2, 30, "native", 10, 20),
                new NoopCognitiveEventBus(), new ToolRuntimeDebugService(), new ObjectMapper(),
                new NativeToolSchemaMapper(registry()), datasetService
        );
    }

    private ToolCallingRequest request(String requestId, String conversationId) {
        return new ToolCallingRequest(
                requestId, conversationId, "przygotuj grafik na sierpien",
                "Create the Store Audit dataset.", "test", "Base prompt",
                new Brain(BrainType.FAST, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.LOW),
                KnowledgeMode.FAST
        );
    }

    private static List<Integer> intRange(int fromInclusive, int toInclusive) {
        List<Integer> values = new ArrayList<>();
        for (int index = fromInclusive; index <= toInclusive; index++) {
            values.add(index);
        }
        return values;
    }

    private static List<Map<String, Object>> records(int fromRow, int toRowInclusive) {
        List<Map<String, Object>> records = new ArrayList<>();
        for (int row = fromRow; row <= toRowInclusive; row++) {
            Map<String, Object> record = new HashMap<>();
            record.put("network", "Biedronka");
            record.put("city", "Miasto Testowe");
            record.put("street", "Ulica Testowa");
            record.put("buildingNumber", String.valueOf(row));
            record.put("postalCode", "00-0" + String.format("%02d", row % 100));
            record.put("fullAddress", "Ulica Testowa " + row + ", Miasto Testowe");
            record.put("sourceRow", row);
            records.add(record);
        }
        return records;
    }

    private static ModelResponse submitScheduleTurn() {
        return new ModelResponse("", "", List.of(new ModelToolCall("call-schedule-" + System.nanoTime(),
                "storedataset__submit_schedule", Map.of("days", List.of(Map.of("day", 1, "storeIndexes", List.of(1, 2, 3)))))),
                "tool_calls", new ModelUsage(0, 0, 0));
    }

    private static ModelResponse toolCallTurn(String name, Map<String, Object> arguments) {
        return new ModelResponse("", "", List.of(new ModelToolCall("call-" + System.nanoTime(), name, arguments)),
                "tool_calls", new ModelUsage(0, 0, 0));
    }

    private static ModelResponse textTurn(String content) {
        return new ModelResponse(content, "", List.of(), "stop", new ModelUsage(0, 0, 0));
    }

    private static ToolRegistry registry() {
        ToolDefinition knowledge = new ToolDefinition("knowledge", "Knowledge workspace.", List.of(
                new ToolOperationDefinition("READ_DOCUMENT", "Read a document.", List.of(
                        new ToolArgumentDefinition("path", "string", true, "Path")
                ), false, ToolSafetyLevel.READ)
        ));
        ToolDefinition location = new ToolDefinition("location", "Geocoding.", List.of(
                new ToolOperationDefinition("GEOCODE_DATASET", "Geocode dataset records.", List.of(
                        new ToolArgumentDefinition("datasetId", "string", false, "Dataset id"),
                        new ToolArgumentDefinition("recordIds", "array", false, "Record ids")
                ), true, ToolSafetyLevel.WRITE)
        ));
        ToolDefinition storeDataset = new ToolDefinition("storedataset", "Canonical dataset.", List.of(
                new ToolOperationDefinition("CREATE_DATASET", "Create dataset.", List.of(
                        new ToolArgumentDefinition("sourceImageCount", "number", true, "Count"),
                        new ToolArgumentDefinition("expectedRecordCount", "number", true, "Expected count"),
                        new ToolArgumentDefinition("records", "array", true, "Records")
                ), true, ToolSafetyLevel.WRITE),
                new ToolOperationDefinition("START_DATASET", "Start dataset.", List.of(
                        new ToolArgumentDefinition("sourceImageCount", "number", true, "Count"),
                        new ToolArgumentDefinition("expectedRecordCount", "number", true, "Expected count"),
                        new ToolArgumentDefinition("records", "array", true, "Records")
                ), true, ToolSafetyLevel.WRITE),
                new ToolOperationDefinition("APPEND_RECORDS", "Append records.", List.of(
                        new ToolArgumentDefinition("datasetId", "string", false, "Dataset id"),
                        new ToolArgumentDefinition("records", "array", true, "Records")
                ), true, ToolSafetyLevel.WRITE),
                new ToolOperationDefinition("FINALIZE_DATASET", "Finalize dataset.", List.of(
                        new ToolArgumentDefinition("datasetId", "string", false, "Dataset id")
                ), true, ToolSafetyLevel.WRITE),
                new ToolOperationDefinition("VERIFY_DATASET", "Verify dataset.", List.of(
                        new ToolArgumentDefinition("datasetId", "string", false, "Dataset id"),
                        new ToolArgumentDefinition("verifications", "array", true, "Verifications")
                ), true, ToolSafetyLevel.WRITE),
                new ToolOperationDefinition("GET_DATASET", "Get dataset.", List.of(
                        new ToolArgumentDefinition("datasetId", "string", false, "Dataset id")
                ), false, ToolSafetyLevel.READ),
                new ToolOperationDefinition("SUBMIT_SCHEDULE", "Submit schedule.", List.of(
                        new ToolArgumentDefinition("datasetId", "string", false, "Dataset id"),
                        new ToolArgumentDefinition("days", "array", true, "Days")
                ), true, ToolSafetyLevel.WRITE)
        ));
        List<ToolDefinition> definitions = List.of(knowledge, location, storeDataset);
        return new ToolRegistry() {
            @Override
            public List<ToolDefinition> definitions() {
                return definitions;
            }

            @Override
            public String promptSection() {
                return "";
            }
        };
    }

    private static final class ScriptedProvider implements AIProvider {

        private final Deque<ModelResponse> turns;
        private int calls;

        private ScriptedProvider(Deque<ModelResponse> turns) {
            this.turns = turns;
        }

        int callCount() {
            return calls;
        }

        @Override
        public String provider() {
            return "stub";
        }

        @Override
        public ChatResponse chat(Brain brain, String prompt) {
            return new ChatResponse("");
        }

        @Override
        public void stream(String conversationId, Brain brain, String prompt, ChatEventSink eventSink) {
        }

        @Override
        public ModelResponse toolChat(Brain brain, List<ModelMessage> messages, List<NativeToolDefinition> tools, AIJobType jobType) {
            calls++;
            return turns.isEmpty() ? textTurn("Oto gotowy grafik.") : turns.poll();
        }
    }

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

    /**
     * Delegates {@code storedataset} calls to a real {@link StoreDatasetTool} and {@code
     * location.GEOCODE_DATASET} to a real {@link LocationTool} (when provided), and {@code
     * knowledge.READ_DOCUMENT} to a canned success.
     */
    private static final class FullToolManager implements ToolManager {

        private final StoreAuditDatasetService datasetService;
        private final StoreDatasetTool storeDatasetTool;
        private final LocationTool locationTool;

        private FullToolManager(StoreAuditDatasetService datasetService, StoreDatasetTool storeDatasetTool, LocationTool locationTool) {
            this.datasetService = datasetService;
            this.storeDatasetTool = storeDatasetTool;
            this.locationTool = locationTool;
        }

        @Override
        public List<JarvisTool> listTools() {
            return List.of();
        }

        @Override
        public Optional<JarvisTool> findTool(String name) {
            return Optional.of(new JarvisTool() {
                @Override
                public String getName() {
                    return name;
                }

                @Override
                public String getDescription() {
                    return "stub";
                }

                @Override
                public ToolResult execute(ToolRequest request) {
                    throw new UnsupportedOperationException("Not used directly");
                }
            });
        }

        @Override
        public ToolResult execute(ToolRequest request) {
            if ("knowledge".equalsIgnoreCase(request.toolName()) && "READ_DOCUMENT".equalsIgnoreCase(request.operation())) {
                return new ToolResult(true, "knowledge", "READ_DOCUMENT", request.requestId(), request.conversationId(),
                        false, List.of(), "Document read", Map.of("content", "workflow procedure"), "", "", false, "");
            }
            if ("storedataset".equalsIgnoreCase(request.toolName())) {
                return storeDatasetTool.execute(request);
            }
            if ("location".equalsIgnoreCase(request.toolName()) && "GEOCODE_DATASET".equalsIgnoreCase(request.operation())) {
                return locationTool.execute(request);
            }
            throw new UnsupportedOperationException("Unexpected tool call: " + request.toolName() + "." + request.operation());
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
