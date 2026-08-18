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
import com.jarvis.common.event.CognitiveEventBus;
import com.jarvis.common.event.CognitiveEventType;
import com.jarvis.common.knowledge.KnowledgeMode;
import com.jarvis.tools.JarvisTool;
import com.jarvis.tools.ToolManager;
import com.jarvis.tools.ToolRequest;
import com.jarvis.tools.ToolResult;
import com.jarvis.tools.ToolRuntimeProperties;
import com.jarvis.tools.dataset.CandidateRecord;
import com.jarvis.tools.dataset.DatasetStage;
import com.jarvis.tools.dataset.GeolocationEntry;
import com.jarvis.tools.dataset.GeolocationStatus;
import com.jarvis.tools.dataset.ScheduleDay;
import com.jarvis.tools.dataset.StoreAuditDataset;
import com.jarvis.tools.dataset.StoreAuditDatasetService;
import com.jarvis.tools.dataset.StoreRecord;
import com.jarvis.tools.dataset.VerificationEntry;
import com.jarvis.tools.schema.ToolArgumentDefinition;
import com.jarvis.tools.schema.ToolDefinition;
import com.jarvis.tools.schema.ToolOperationDefinition;
import com.jarvis.tools.schema.ToolRegistry;
import com.jarvis.tools.schema.ToolSafetyLevel;
import com.jarvis.tools.workflow.StoreAuditWorkflowCompletionValidator;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for the completion gate running on EVERY loop exit path, not just the
 * content-based one. The exact reported bug: a native model turn with {@code content=""} and
 * {@code toolCalls=[]} but existing tool results fell straight through to {@code
 * FINAL_SYNTHESIS_REQUIRED} without ever consulting {@link StoreAuditWorkflowCompletionValidator}
 * via {@link com.jarvis.tools.workflow.WorkflowCompletionValidator#assess}, letting a Store Audit
 * dataset stuck at {@code GEOLOCATED} (VERIFY_DATASET skipped, SUBMIT_SCHEDULE never called)
 * produce a "geocoding summary" as if the task were finished.
 */
class NativeToolLoopServiceCompletionGateTest {

    private static final String WORKFLOW_DOCUMENT_PATH = "Work/Scheduling/StoreAuditScheduleWorkflow.md";

    // REGRESSION TEST 9: dataset stage=GEOLOCATED, native model returns an empty turn (no tool
    // calls, no text) after results already exist. The loop must NOT return FINAL_SYNTHESIS_REQUIRED
    // (finalAnswer=""); it must re-enter and let the model reach SUBMIT_SCHEDULE.
    @Test
    void emptyResponseAfterToolResultsDoesNotBypassTheCompletionGateWhenDatasetIsNotScheduled() {
        StoreAuditDatasetService datasetService = new StoreAuditDatasetService(new NoopCognitiveEventBus());
        StoreAuditDataset geolocated = buildGeolocatedDataset(datasetService);
        List<String> recordIds = geolocated.stores().stream().map(StoreRecord::id).toList();

        Deque<ModelResponse> turns = new ArrayDeque<>();
        turns.add(toolCallTurn("knowledge__read_document", Map.of("path", WORKFLOW_DOCUMENT_PATH)));
        turns.add(toolCallTurn("storedataset__get_dataset", Map.of("datasetId", geolocated.datasetId())));
        // The exact reported bug's shape: neither a tool call nor any text, with results already present.
        turns.add(emptyTurn());
        turns.add(toolCallTurn("storedataset__submit_schedule", Map.of(
                "datasetId", geolocated.datasetId(), "days", List.of(Map.of("day", 1, "storeIds", recordIds)))));
        turns.add(textTurn("Oto gotowy grafik."));
        ScriptedProvider provider = new ScriptedProvider(turns);
        FakeToolManager toolManager = new FakeToolManager(datasetService);

        NativeToolLoopService service = new NativeToolLoopService(
                List.of(provider), toolManager, query -> ToolIntent.LOCATION,
                new ToolRuntimeProperties(true, 10, 10, 2, 30, "native", 10, 20),
                new NoopCognitiveEventBus(), new ToolRuntimeDebugService(), new ObjectMapper(),
                new NativeToolSchemaMapper(registry()), datasetService
        );

        ToolCallingResult result = service.execute(new ToolCallingRequest(
                "request-1", "conversation-1", "przygotuj grafik na sierpien",
                "Finish the Store Audit schedule.", "test", "Base prompt",
                new Brain(BrainType.FAST, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.LOW),
                KnowledgeMode.FAST
        ));

        assertThat(result.handled()).isTrue();
        // The bug: this used to be "" (FINAL_SYNTHESIS_REQUIRED short-circuit right after the empty
        // turn). The fix: the loop keeps going until the model actually submits the schedule.
        assertThat(result.finalAnswer()).isEqualTo("Oto gotowy grafik.");
        assertThat(datasetService.getDataset(geolocated.datasetId()).orElseThrow().stage()).isEqualTo(DatasetStage.SCHEDULED);
    }

    // REGRESSION TEST 10: dataset stage=SCHEDULED (already complete) - the completion gate must
    // report success immediately and let the loop finish with the model's final answer, no re-entry.
    @Test
    void terminalScheduledStateLetsTheLoopFinishWithoutReentry() {
        StoreAuditDatasetService datasetService = new StoreAuditDatasetService(new NoopCognitiveEventBus());
        StoreAuditDataset scheduled = buildScheduledDataset(datasetService);

        Deque<ModelResponse> turns = new ArrayDeque<>();
        turns.add(toolCallTurn("storedataset__get_dataset", Map.of("datasetId", scheduled.datasetId())));
        turns.add(textTurn("Oto gotowy grafik: 3 sklepy zaplanowane."));
        ScriptedProvider provider = new ScriptedProvider(turns);
        FakeToolManager toolManager = new FakeToolManager(datasetService);

        NativeToolLoopService service = new NativeToolLoopService(
                List.of(provider), toolManager, query -> ToolIntent.LOCATION,
                new ToolRuntimeProperties(true, 10, 10, 2, 30, "native", 10, 20),
                new NoopCognitiveEventBus(), new ToolRuntimeDebugService(), new ObjectMapper(),
                new NativeToolSchemaMapper(registry()), datasetService
        );

        ToolCallingResult result = service.execute(new ToolCallingRequest(
                "request-1", "conversation-1", "pokaz gotowy grafik",
                "Show the finished schedule.", "test", "Base prompt",
                new Brain(BrainType.FAST, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.LOW),
                KnowledgeMode.FAST
        ));

        assertThat(result.handled()).isTrue();
        assertThat(result.finalAnswer()).isEqualTo("Oto gotowy grafik: 3 sklepy zaplanowane.");
        // Exactly the 2 scripted turns ran - no completion-gate re-entry was needed.
        assertThat(provider.callCount()).isEqualTo(2);
    }

    private StoreAuditDataset buildGeolocatedDataset(StoreAuditDatasetService datasetService) {
        StoreAuditDataset dataset = datasetService.createDataset("request-1", 1, 0, List.of("att-1"), List.of(
                new CandidateRecord("Biedronka", "Miasto", "Ulica", "1", "00-001", "Ulica 1, 00-001 Miasto", "att-1", 1),
                new CandidateRecord("Biedronka", "Miasto", "Ulica", "2", "00-002", "Ulica 2, 00-002 Miasto", "att-1", 2),
                new CandidateRecord("Biedronka", "Miasto", "Ulica", "3", "00-003", "Ulica 3, 00-003 Miasto", "att-1", 3)
        )).dataset();
        var verifications = dataset.stores().stream().map(record -> new VerificationEntry(record.id(), "VERIFIED", "", "")).toList();
        StoreAuditDataset locked = datasetService.verifyDataset(dataset.datasetId(), verifications).dataset();
        var geolocations = locked.stores().stream()
                .map(record -> new GeolocationEntry(record.id(), GeolocationStatus.RESOLVED, 52.0, 21.0)).toList();
        return datasetService.updateGeolocation(dataset.datasetId(), geolocations).dataset();
    }

    private StoreAuditDataset buildScheduledDataset(StoreAuditDatasetService datasetService) {
        StoreAuditDataset geolocated = buildGeolocatedDataset(datasetService);
        List<String> ids = geolocated.stores().stream().map(StoreRecord::id).toList();
        return datasetService.submitSchedule(geolocated.datasetId(), List.of(new ScheduleDay(1, ids))).dataset();
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
                        new ToolArgumentDefinition("path", "string", true, "Logical path")
                ), false, ToolSafetyLevel.READ)
        ));
        ToolDefinition storeDataset = new ToolDefinition("storedataset", "Canonical dataset.", List.of(
                new ToolOperationDefinition("GET_DATASET", "Get dataset.", List.of(
                        new ToolArgumentDefinition("datasetId", "string", true, "Dataset id")
                ), false, ToolSafetyLevel.READ),
                new ToolOperationDefinition("SUBMIT_SCHEDULE", "Submit schedule.", List.of(
                        new ToolArgumentDefinition("datasetId", "string", true, "Dataset id"),
                        new ToolArgumentDefinition("days", "array", true, "Day groupings")
                ), true, ToolSafetyLevel.WRITE)
        ));
        return new ToolRegistry() {
            @Override
            public List<ToolDefinition> definitions() {
                return List.of(knowledge, storeDataset);
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
     * Stubs {@code knowledge.READ_DOCUMENT}/{@code storedataset.GET_DATASET} with canned success
     * results, but delegates {@code storedataset.SUBMIT_SCHEDULE} to the real {@link
     * StoreAuditDatasetService} so the dataset genuinely reaches {@code SCHEDULED} - the completion
     * gate reads real service state, not the tool call's own claimed result.
     */
    private static final class FakeToolManager implements ToolManager {

        private final StoreAuditDatasetService datasetService;

        private FakeToolManager(StoreAuditDatasetService datasetService) {
            this.datasetService = datasetService;
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
            if ("storedataset".equalsIgnoreCase(request.toolName()) && "GET_DATASET".equalsIgnoreCase(request.operation())) {
                String datasetId = String.valueOf(request.arguments().get("datasetId"));
                var dataset = datasetService.getDataset(datasetId).orElseThrow();
                return new ToolResult(true, "storedataset", "GET_DATASET", request.requestId(), request.conversationId(),
                        false, List.of(datasetId), "Dataset info",
                        Map.of("datasetId", datasetId, "stage", dataset.stage().name(), "count", dataset.stores().size()),
                        "", "", false, "");
            }
            if ("storedataset".equalsIgnoreCase(request.toolName()) && "SUBMIT_SCHEDULE".equalsIgnoreCase(request.operation())) {
                String datasetId = String.valueOf(request.arguments().get("datasetId"));
                List<Object> rawDays = (List<Object>) request.arguments().get("days");
                List<ScheduleDay> days = rawDays.stream().map(raw -> {
                    Map<String, Object> map = (Map<String, Object>) raw;
                    List<String> storeIds = (List<String>) map.get("storeIds");
                    int day = ((Number) map.get("day")).intValue();
                    return new ScheduleDay(day, storeIds);
                }).toList();
                var outcome = datasetService.submitSchedule(datasetId, days);
                return new ToolResult(outcome.success(), "storedataset", "SUBMIT_SCHEDULE", request.requestId(), request.conversationId(),
                        outcome.success(), List.of(datasetId), outcome.message(),
                        Map.of("datasetId", datasetId, "stage", outcome.dataset() == null ? "" : outcome.dataset().stage().name()),
                        outcome.success() ? "" : "STORE_DATASET_INVARIANT_VIOLATION", outcome.success() ? "" : outcome.message(), false, "");
            }
            throw new UnsupportedOperationException("Unexpected tool call: " + request.toolName() + "." + request.operation());
        }
    }

    private static final class NoopCognitiveEventBus implements CognitiveEventBus {

        @Override
        public void startRequest(String requestId, String conversationId, Consumer<com.jarvis.common.event.CognitiveEvent> sink) {
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
