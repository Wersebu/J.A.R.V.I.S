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
 * Regression tests for Core-owned canonical Store Audit dataset identity: the exact reported
 * production bug had the model hallucinate a plausible-looking but entirely invented {@code
 * datasetId} ({@code dataset_0_0_1724357613666_1724357613666}) after a successful {@code
 * START_DATASET}, and {@link NativeToolLoopService} let that FAILED call's own unverified
 * argument overwrite the loop's canonical {@code activeDatasetId} - corrupting workflow state so
 * completely that the completion gate then saw {@code stage=n/a} and reported the task complete,
 * even though the real dataset was still sitting at {@code BUILDING} with 8/23 records. These
 * tests prove the canonical dataset id is Core-owned: it can only ever be set from a Core-confirmed
 * successful result, a model-supplied mismatch is rejected before ever reaching the real tool
 * (never wasting a real dataset-service call on an id that cannot exist), and the workflow can
 * always recover and reach {@code SCHEDULED} after exactly one bad id.
 */
class NativeToolLoopServiceCanonicalDatasetIdentityTest {

    private static final LocationProperties LOCATION_PROPERTIES = new LocationProperties(
            true, "https://nominatim.example", "https://osrm.example", "Test-Agent/1.0",
            0, 25, 8, Duration.ofSeconds(1), Duration.ofSeconds(1), 5);
    private static final String WORKFLOW_DOCUMENT_PATH = "Work/Scheduling/StoreAuditScheduleWorkflow.md";

    // TEST 2: a canonical dataset A already exists (successful START_DATASET). The model then
    // calls APPEND_RECORDS with a completely invented datasetId - the call must be rejected as
    // STORE_DATASET_ID_MISMATCH, WITHOUT ever reaching the real StoreDatasetTool/dataset service,
    // and the canonical dataset must be completely unaffected (still BUILDING, still 8 records).
    @Test
    void appendWithAFabricatedDatasetIdIsRejectedWithoutTouchingTheCanonicalDataset() {
        StoreAuditDatasetService datasetService = new StoreAuditDatasetService(new NoopCognitiveEventBus());
        StoreDatasetTool storeDatasetTool = new StoreDatasetTool(datasetService);
        datasetService.registerAttachments("request-1", "conversation-1", List.of());
        FullToolManager toolManager = new FullToolManager(datasetService, storeDatasetTool, null);

        Deque<ModelResponse> turns = new ArrayDeque<>();
        turns.add(toolCallTurn("storedataset__start_dataset", Map.of(
                "sourceImageCount", 1, "expectedRecordCount", 23,
                "records", records("Biedronka", 1, 8))));
        // The exact production shape: a plausible-looking but entirely invented id.
        turns.add(toolCallTurn("storedataset__append_records", Map.of(
                "datasetId", "dataset_0_0_1724357613666_1724357613666",
                "records", records("Biedronka", 9, 10))));
        turns.add(textTurn("Napotkalem blad identyfikatora zestawu danych."));
        ScriptedProvider provider = new ScriptedProvider(turns);

        NativeToolLoopService service = service(provider, toolManager, datasetService);
        ToolCallingResult result = service.execute(request("request-1", "conversation-1"));

        assertThat(result.handled()).isTrue();
        ToolResult mismatch = result.results().stream()
                .filter(toolResult -> "STORE_DATASET_ID_MISMATCH".equals(toolResult.errorCode()))
                .findFirst().orElseThrow();
        assertThat(mismatch.success()).isFalse();
        assertThat(mismatch.data().get("activeDatasetId")).isNotEqualTo("dataset_0_0_1724357613666_1724357613666");

        StoreAuditDataset canonical = datasetService.findLatestForConversation("conversation-1").orElseThrow();
        assertThat(canonical.stage()).isEqualTo(DatasetStage.BUILDING);
        assertThat(canonical.stores()).hasSize(8);
        assertThat(datasetService.getDataset("dataset_0_0_1724357613666_1724357613666")).isEmpty();
    }

    // TEST 3: after the fabricated-id mismatch above, the very next model turn is empty (no tool
    // call, no text) - the exact reported shape that used to make the completion gate see
    // stage=n/a and wrongly report complete=true. The loop must keep going and eventually let the
    // model recover and reach a real answer, never silently accepting "" right after the mismatch.
    @Test
    void completionGateStaysAccurateAfterAFabricatedDatasetIdFollowedByAnEmptyTurn() {
        StoreAuditDatasetService datasetService = new StoreAuditDatasetService(new NoopCognitiveEventBus());
        StoreDatasetTool storeDatasetTool = new StoreDatasetTool(datasetService);
        datasetService.registerAttachments("request-1", "conversation-1", List.of());
        FullToolManager toolManager = new FullToolManager(datasetService, storeDatasetTool, null);

        Deque<ModelResponse> turns = new ArrayDeque<>();
        turns.add(toolCallTurn("storedataset__start_dataset", Map.of(
                "sourceImageCount", 1, "expectedRecordCount", 8,
                "records", records("Biedronka", 1, 8))));
        turns.add(toolCallTurn("storedataset__append_records", Map.of(
                "datasetId", "dataset_0_0_fake", "records", records("Biedronka", 9, 9))));
        turns.add(emptyTurn());
        ScriptedProvider provider = new ScriptedProvider(turns);

        NativeToolLoopService service = service(provider, toolManager, datasetService);
        ToolCallingResult result = service.execute(request("request-1", "conversation-1"));

        assertThat(result.handled()).isTrue();
        // The bug: this used to return "" immediately here (complete=true logged wrongly because
        // activeDatasetId had been corrupted to the fake id and stage read back as n/a). The fix:
        // the real dataset (8/8, stage=BUILDING, not yet FINALIZE_DATASET'd) is still incomplete,
        // so the gate keeps re-entering rather than accepting the empty turn as done.
        assertThat(provider.callCount()).isGreaterThan(3);
        StoreAuditDataset canonical = datasetService.findLatestForConversation("conversation-1").orElseThrow();
        assertThat(canonical.stores()).hasSize(8);
    }

    // TEST 4: while dataset A is active, the model can call APPEND_RECORDS without a datasetId
    // argument at all - Core injects the canonical active id automatically.
    @Test
    void modelIsNotRequiredToSupplyDatasetIdWhileAWorkflowIsActive() {
        StoreAuditDatasetService datasetService = new StoreAuditDatasetService(new NoopCognitiveEventBus());
        StoreDatasetTool storeDatasetTool = new StoreDatasetTool(datasetService);
        datasetService.registerAttachments("request-1", "conversation-1", List.of());
        FullToolManager toolManager = new FullToolManager(datasetService, storeDatasetTool, null);

        Deque<ModelResponse> turns = new ArrayDeque<>();
        turns.add(toolCallTurn("storedataset__start_dataset", Map.of(
                "sourceImageCount", 1, "expectedRecordCount", 10,
                "records", records("Biedronka", 1, 8))));
        // No datasetId at all - Core must inject the canonical active dataset id.
        turns.add(toolCallTurn("storedataset__append_records", Map.of("records", records("Biedronka", 9, 10))));
        turns.add(textTurn("Zestaw danych zaktualizowany."));
        ScriptedProvider provider = new ScriptedProvider(turns);

        NativeToolLoopService service = service(provider, toolManager, datasetService);
        ToolCallingResult result = service.execute(request("request-1", "conversation-1"));

        assertThat(result.handled()).isTrue();
        StoreAuditDataset canonical = datasetService.findLatestForConversation("conversation-1").orElseThrow();
        assertThat(canonical.stores()).hasSize(10);
        assertThat(result.results()).noneMatch(toolResult -> "STORE_DATASET_ID_MISMATCH".equals(toolResult.errorCode()));
    }

    // TEST 5: the model explicitly supplies the correct, canonical datasetId - the call proceeds
    // completely normally (explicit-but-correct ids are never penalized).
    @Test
    void explicitCorrectDatasetIdWorksNormally() {
        StoreAuditDatasetService datasetService = new StoreAuditDatasetService(new NoopCognitiveEventBus());
        StoreDatasetTool storeDatasetTool = new StoreDatasetTool(datasetService);
        datasetService.registerAttachments("request-1", "conversation-1", List.of());
        FullToolManager toolManager = new FullToolManager(datasetService, storeDatasetTool, null);

        ToolCallingResult started = new NativeToolLoopService(
                List.of(new ScriptedProvider(new ArrayDeque<>(List.of(
                        toolCallTurn("storedataset__start_dataset", Map.of(
                                "sourceImageCount", 1, "expectedRecordCount", 9,
                                "records", records("Biedronka", 1, 8))),
                        textTurn("start done"))))),
                toolManager, query -> ToolIntent.LOCATION,
                new ToolRuntimeProperties(true, 10, 10, 2, 30, "native", 10, 20),
                new NoopCognitiveEventBus(), new ToolRuntimeDebugService(), new ObjectMapper(),
                new NativeToolSchemaMapper(registry()), datasetService
        ).execute(request("request-1", "conversation-1"));
        assertThat(started.handled()).isTrue();
        String canonicalId = datasetService.findLatestForConversation("conversation-1").orElseThrow().datasetId();

        Deque<ModelResponse> turns = new ArrayDeque<>();
        turns.add(toolCallTurn("storedataset__append_records", Map.of(
                "datasetId", canonicalId, "records", records("Biedronka", 9, 9))));
        turns.add(textTurn("Zestaw danych zaktualizowany."));
        ScriptedProvider provider = new ScriptedProvider(turns);
        NativeToolLoopService service = service(provider, toolManager, datasetService);

        ToolCallingResult result = service.execute(request("request-2", "conversation-1"));

        assertThat(result.handled()).isTrue();
        assertThat(datasetService.getDataset(canonicalId).orElseThrow().stores()).hasSize(9);
    }

    // TEST 6: the model explicitly supplies a WRONG datasetId - rejected as
    // STORE_DATASET_ID_MISMATCH, canonical dataset remains active/unaffected.
    @Test
    void explicitWrongDatasetIdIsRejectedAsAMismatch() {
        StoreAuditDatasetService datasetService = new StoreAuditDatasetService(new NoopCognitiveEventBus());
        StoreDatasetTool storeDatasetTool = new StoreDatasetTool(datasetService);
        datasetService.registerAttachments("request-1", "conversation-1", List.of());
        FullToolManager toolManager = new FullToolManager(datasetService, storeDatasetTool, null);

        Deque<ModelResponse> turns = new ArrayDeque<>();
        turns.add(toolCallTurn("storedataset__start_dataset", Map.of(
                "sourceImageCount", 1, "expectedRecordCount", 8,
                "records", records("Biedronka", 1, 8))));
        turns.add(toolCallTurn("storedataset__get_dataset", Map.of("datasetId", "completely-different-dataset")));
        turns.add(textTurn("Sprawdzono."));
        ScriptedProvider provider = new ScriptedProvider(turns);

        NativeToolLoopService service = service(provider, toolManager, datasetService);
        ToolCallingResult result = service.execute(request("request-1", "conversation-1"));

        assertThat(result.handled()).isTrue();
        assertThat(result.results()).anyMatch(toolResult -> "STORE_DATASET_ID_MISMATCH".equals(toolResult.errorCode()));
        assertThat(datasetService.findLatestForConversation("conversation-1").orElseThrow().stores()).hasSize(8);
    }

    // TEST 7: FAILED FINALIZE_DATASET with a fake id - never reaches the real tool, canonical
    // dataset (and its stage) is completely unaffected.
    @Test
    void finalizeWithAFakeDatasetIdNeverTouchesTheCanonicalDataset() {
        StoreAuditDatasetService datasetService = new StoreAuditDatasetService(new NoopCognitiveEventBus());
        StoreDatasetTool storeDatasetTool = new StoreDatasetTool(datasetService);
        datasetService.registerAttachments("request-1", "conversation-1", List.of());
        FullToolManager toolManager = new FullToolManager(datasetService, storeDatasetTool, null);

        Deque<ModelResponse> turns = new ArrayDeque<>();
        turns.add(toolCallTurn("storedataset__start_dataset", Map.of(
                "sourceImageCount", 1, "expectedRecordCount", 8,
                "records", records("Biedronka", 1, 8))));
        turns.add(toolCallTurn("storedataset__finalize_dataset", Map.of("datasetId", "fake-id")));
        turns.add(textTurn("done"));
        ScriptedProvider provider = new ScriptedProvider(turns);

        NativeToolLoopService service = service(provider, toolManager, datasetService);
        ToolCallingResult result = service.execute(request("request-1", "conversation-1"));

        assertThat(result.results()).anyMatch(toolResult -> "STORE_DATASET_ID_MISMATCH".equals(toolResult.errorCode()));
        assertThat(datasetService.findLatestForConversation("conversation-1").orElseThrow().stage()).isEqualTo(DatasetStage.BUILDING);
    }

    // TEST 8: FAILED VERIFY_DATASET with a fake id - never reaches the real tool.
    @Test
    void verifyWithAFakeDatasetIdNeverTouchesTheCanonicalDataset() {
        StoreAuditDatasetService datasetService = new StoreAuditDatasetService(new NoopCognitiveEventBus());
        StoreDatasetTool storeDatasetTool = new StoreDatasetTool(datasetService);
        datasetService.registerAttachments("request-1", "conversation-1", List.of());
        FullToolManager toolManager = new FullToolManager(datasetService, storeDatasetTool, null);

        Deque<ModelResponse> turns = new ArrayDeque<>();
        turns.add(toolCallTurn("storedataset__start_dataset", Map.of(
                "sourceImageCount", 1, "expectedRecordCount", 8,
                "records", records("Biedronka", 1, 8))));
        turns.add(toolCallTurn("storedataset__finalize_dataset", Map.of()));
        turns.add(toolCallTurn("storedataset__verify_dataset", Map.of(
                "datasetId", "fake-id", "verifications", List.of())));
        turns.add(textTurn("done"));
        ScriptedProvider provider = new ScriptedProvider(turns);

        NativeToolLoopService service = service(provider, toolManager, datasetService);
        ToolCallingResult result = service.execute(request("request-1", "conversation-1"));

        assertThat(result.results()).anyMatch(toolResult -> "STORE_DATASET_ID_MISMATCH".equals(toolResult.errorCode()));
        assertThat(datasetService.findLatestForConversation("conversation-1").orElseThrow().stage()).isEqualTo(DatasetStage.EXTRACTED);
    }

    // TEST 9: FAILED GEOCODE_DATASET with a fake id - the real LocationTool must never even be
    // invoked (proven with a throwing stub in place of a working one).
    @Test
    void geocodeWithAFakeDatasetIdNeverReachesTheRealLocationTool() {
        StoreAuditDatasetService datasetService = new StoreAuditDatasetService(new NoopCognitiveEventBus());
        StoreDatasetTool storeDatasetTool = new StoreDatasetTool(datasetService);
        LocationTool locationTool = new LocationTool(new AlwaysThrowingGeocodingClient(), new UnusedRoutingClient(), LOCATION_PROPERTIES, datasetService);
        datasetService.registerAttachments("request-1", "conversation-1", List.of());
        FullToolManager toolManager = new FullToolManager(datasetService, storeDatasetTool, locationTool);

        Deque<ModelResponse> turns = new ArrayDeque<>();
        turns.add(toolCallTurn("storedataset__start_dataset", Map.of(
                "sourceImageCount", 1, "expectedRecordCount", 8,
                "records", records("Biedronka", 1, 8))));
        turns.add(toolCallTurn("location__geocode_dataset", Map.of("datasetId", "fake-id")));
        turns.add(textTurn("done"));
        ScriptedProvider provider = new ScriptedProvider(turns);

        NativeToolLoopService service = service(provider, toolManager, datasetService);
        // AlwaysThrowingGeocodingClient would blow up the whole test if GEOCODE_DATASET actually
        // reached LocationTool - it never should, since the mismatch is rejected before dispatch.
        ToolCallingResult result = service.execute(request("request-1", "conversation-1"));

        assertThat(result.results()).anyMatch(toolResult -> "STORE_DATASET_ID_MISMATCH".equals(toolResult.errorCode()));
    }

    // TEST 10: FAILED GET_DATASET with a fake id - never reaches the real tool, and (the original
    // vulnerable path) never overwrites the canonical activeDatasetId either.
    @Test
    void getDatasetWithAFakeDatasetIdNeverTouchesOrOverwritesTheCanonicalDataset() {
        StoreAuditDatasetService datasetService = new StoreAuditDatasetService(new NoopCognitiveEventBus());
        StoreDatasetTool storeDatasetTool = new StoreDatasetTool(datasetService);
        datasetService.registerAttachments("request-1", "conversation-1", List.of());
        FullToolManager toolManager = new FullToolManager(datasetService, storeDatasetTool, null);

        Deque<ModelResponse> turns = new ArrayDeque<>();
        turns.add(toolCallTurn("storedataset__start_dataset", Map.of(
                "sourceImageCount", 1, "expectedRecordCount", 8,
                "records", records("Biedronka", 1, 8))));
        turns.add(toolCallTurn("storedataset__get_dataset", Map.of("datasetId", "fake-id")));
        turns.add(toolCallTurn("storedataset__append_records", Map.of("records", records("Biedronka", 9, 9))));
        turns.add(textTurn("done"));
        ScriptedProvider provider = new ScriptedProvider(turns);

        NativeToolLoopService service = service(provider, toolManager, datasetService);
        ToolCallingResult result = service.execute(request("request-1", "conversation-1"));

        assertThat(result.results()).anyMatch(toolResult -> "STORE_DATASET_ID_MISMATCH".equals(toolResult.errorCode()));
        // The subsequent omitted-datasetId APPEND_RECORDS still landed on the real canonical
        // dataset (9 records) - proof the fake GET_DATASET call never hijacked activeDatasetId.
        assertThat(datasetService.findLatestForConversation("conversation-1").orElseThrow().stores()).hasSize(9);
    }

    // TEST 11: the full production shape end to end - 8 records via START_DATASET, a fabricated-id
    // APPEND rejected, then APPEND recovers with the remaining 15 (23 total), through FINALIZE,
    // VERIFY, the required workflow document, GEOCODE_DATASET, and SUBMIT_SCHEDULE, reaching
    // SCHEDULED with every one of the 23 records intact.
    @Test
    void fullTwentyThreeRecordWorkflowReachesScheduledDespiteOneFabricatedDatasetIdMidway() {
        StoreAuditDatasetService datasetService = new StoreAuditDatasetService(new NoopCognitiveEventBus());
        StoreDatasetTool storeDatasetTool = new StoreDatasetTool(datasetService);
        LocationTool locationTool = new LocationTool(new AlwaysResolvingGeocodingClient(), new UnusedRoutingClient(), LOCATION_PROPERTIES, datasetService);
        datasetService.registerAttachments("request-1", "conversation-1", List.of());
        FullToolManager toolManager = new FullToolManager(datasetService, storeDatasetTool, locationTool);

        Deque<ModelResponse> turns = new ArrayDeque<>();
        turns.add(toolCallTurn("storedataset__start_dataset", Map.of(
                "sourceImageCount", 2, "expectedRecordCount", 23,
                "records", records("Biedronka", 1, 8))));
        // Hallucinated id - rejected, canonical dataset (8 records) untouched.
        turns.add(toolCallTurn("storedataset__append_records", Map.of(
                "datasetId", "dataset_0_0_1724357613666_1724357613666",
                "records", records("Stokrotka", 9, 23))));
        // Recovers - no datasetId needed, Core injects the canonical one.
        turns.add(toolCallTurn("storedataset__append_records", Map.of("records", records("Stokrotka", 9, 23))));
        turns.add(toolCallTurn("storedataset__finalize_dataset", Map.of()));
        turns.add(toolCallTurn("knowledge__read_document", Map.of("path", WORKFLOW_DOCUMENT_PATH)));
        turns.add(verifyAllTurn());
        turns.add(toolCallTurn("location__geocode_dataset", Map.of()));
        turns.add(submitScheduleTurn());
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
        StoreAuditDataset finalDataset = datasetService.findLatestForConversation("conversation-1").orElseThrow();
        assertThat(finalDataset.stage()).isEqualTo(DatasetStage.SCHEDULED);
        assertThat(finalDataset.stores()).hasSize(23);
    }

    // TEST 12: mid-workflow recovery - START succeeds (8 records), model hallucinates a
    // datasetId for APPEND (rejected), then immediately retries APPEND correctly (omitting
    // datasetId, letting Core inject the canonical one) - the workflow continues without
    // restarting and without losing the first 8 records.
    @Test
    void workflowRecoversImmediatelyAfterOneBadDatasetIdWithoutLosingEarlierRecords() {
        StoreAuditDatasetService datasetService = new StoreAuditDatasetService(new NoopCognitiveEventBus());
        StoreDatasetTool storeDatasetTool = new StoreDatasetTool(datasetService);
        datasetService.registerAttachments("request-1", "conversation-1", List.of());
        FullToolManager toolManager = new FullToolManager(datasetService, storeDatasetTool, null);

        Deque<ModelResponse> turns = new ArrayDeque<>();
        turns.add(toolCallTurn("storedataset__start_dataset", Map.of(
                "sourceImageCount", 1, "expectedRecordCount", 23,
                "records", records("Biedronka", 1, 8))));
        turns.add(toolCallTurn("storedataset__append_records", Map.of(
                "datasetId", "dataset_0_0_bad", "records", records("Stokrotka", 9, 23))));
        turns.add(toolCallTurn("storedataset__append_records", Map.of("records", records("Stokrotka", 9, 23))));
        turns.add(textTurn("Zestaw danych ma teraz 23 rekordy."));
        ScriptedProvider provider = new ScriptedProvider(turns);

        NativeToolLoopService service = service(provider, toolManager, datasetService);
        ToolCallingResult result = service.execute(request("request-1", "conversation-1"));

        assertThat(result.handled()).isTrue();
        StoreAuditDataset canonical = datasetService.findLatestForConversation("conversation-1").orElseThrow();
        assertThat(canonical.stores()).hasSize(23);
        // The original 8 records are still exactly those 8 - not re-created, not lost.
        assertThat(canonical.stores().stream().filter(record -> "Biedronka".equals(record.network())).count()).isEqualTo(8);
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

    private static List<Map<String, Object>> records(String network, int fromRow, int toRowInclusive) {
        List<Map<String, Object>> records = new ArrayList<>();
        for (int row = fromRow; row <= toRowInclusive; row++) {
            Map<String, Object> record = new HashMap<>();
            record.put("network", network);
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

    private static ModelResponse verifyAllTurn() {
        return new ModelResponse("", "", List.of(new ModelToolCall("call-verify-" + System.nanoTime(),
                "storedataset__verify_dataset", Map.of("verifications", List.of("__ALL__")))),
                "tool_calls", new ModelUsage(0, 0, 0));
    }

    private static ModelResponse submitScheduleTurn() {
        return new ModelResponse("", "", List.of(new ModelToolCall("call-schedule-" + System.nanoTime(),
                "storedataset__submit_schedule", Map.of("days", List.of("__ALL_ONE_DAY__")))),
                "tool_calls", new ModelUsage(0, 0, 0));
    }

    private static ModelResponse toolCallTurn(String name, Map<String, Object> arguments) {
        return new ModelResponse("", "", List.of(new ModelToolCall("call-" + System.nanoTime(), name, arguments)),
                "tool_calls", new ModelUsage(0, 0, 0));
    }

    private static ModelResponse textTurn(String content) {
        return new ModelResponse(content, "", List.of(), "stop", new ModelUsage(0, 0, 0));
    }

    private static ModelResponse emptyTurn() {
        return new ModelResponse("", "", List.of(), "stop", new ModelUsage(0, 0, 0));
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
            return turns.isEmpty() ? textTurn("") : turns.poll();
        }
    }

    /**
     * Resolves any address deterministically - only that every geocode call succeeds matters here.
     */
    private static final class AlwaysResolvingGeocodingClient implements GeocodingClient {

        @Override
        public GeocodeResult geocode(String query) {
            double latitude = 50.0 + (Math.abs(query.hashCode()) % 1000) / 1000d;
            double longitude = 20.0 + (Math.abs(query.hashCode()) % 500) / 1000d;
            return GeocodeResult.resolved(query, latitude, longitude, query);
        }
    }

    /**
     * Proves GEOCODE_DATASET with a mismatched datasetId never reaches the real geocoding client -
     * any invocation fails the test immediately.
     */
    private static final class AlwaysThrowingGeocodingClient implements GeocodingClient {

        @Override
        public GeocodeResult geocode(String query) {
            throw new AssertionError("GeocodingClient must never be invoked for a rejected STORE_DATASET_ID_MISMATCH call");
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
     * location.GEOCODE_DATASET} to a real {@link LocationTool} (when provided) - resolves the
     * scripted turns' placeholder {@code "__ALL__"}/{@code "__ALL_ONE_DAY__"} sentinels against
     * the real canonical dataset's current record ids, since those genuinely aren't known ahead of
     * time by the test script.
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
        @SuppressWarnings("unchecked")
        public ToolResult execute(ToolRequest request) {
            if ("knowledge".equalsIgnoreCase(request.toolName()) && "READ_DOCUMENT".equalsIgnoreCase(request.operation())) {
                return new ToolResult(true, "knowledge", "READ_DOCUMENT", request.requestId(), request.conversationId(),
                        false, List.of(), "Document read", Map.of("content", "workflow procedure"), "", "", false, "");
            }
            if ("storedataset".equalsIgnoreCase(request.toolName())) {
                String datasetId = String.valueOf(request.arguments().get("datasetId"));
                if ("VERIFY_DATASET".equalsIgnoreCase(request.operation())
                        && List.of("__ALL__").equals(request.arguments().get("verifications"))) {
                    return storeDatasetTool.execute(withArguments(request, Map.of(
                            "datasetId", datasetId, "verifications", allVerifications(datasetId))));
                }
                if ("SUBMIT_SCHEDULE".equalsIgnoreCase(request.operation())
                        && List.of("__ALL_ONE_DAY__").equals(request.arguments().get("days"))) {
                    return storeDatasetTool.execute(withArguments(request, Map.of(
                            "datasetId", datasetId, "days", oneDaySchedule(datasetId))));
                }
                return storeDatasetTool.execute(request);
            }
            if ("location".equalsIgnoreCase(request.toolName()) && "GEOCODE_DATASET".equalsIgnoreCase(request.operation())) {
                return locationTool.execute(request);
            }
            throw new UnsupportedOperationException("Unexpected tool call: " + request.toolName() + "." + request.operation());
        }

        private ToolRequest withArguments(ToolRequest request, Map<String, Object> arguments) {
            return new ToolRequest(request.toolName(), request.operation(), request.conversationId(), request.requestId(),
                    request.reason(), request.reasoningSummary(), arguments);
        }

        private List<Map<String, Object>> allVerifications(String datasetId) {
            StoreAuditDataset dataset = datasetService.getDataset(datasetId).orElseThrow();
            return dataset.stores().stream()
                    .map(record -> Map.<String, Object>of("recordId", record.id(), "status", "VERIFIED"))
                    .toList();
        }

        private List<Map<String, Object>> oneDaySchedule(String datasetId) {
            StoreAuditDataset dataset = datasetService.getDataset(datasetId).orElseThrow();
            List<String> ids = dataset.stores().stream().map(record -> record.id()).toList();
            return List.of(Map.of("day", 1, "storeIds", ids));
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
