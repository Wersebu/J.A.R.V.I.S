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
import com.jarvis.tools.dataset.GeolocationEntry;
import com.jarvis.tools.dataset.GeolocationStatus;
import com.jarvis.tools.dataset.StoreAuditDataset;
import com.jarvis.tools.dataset.StoreAuditDatasetService;
import com.jarvis.tools.dataset.StoreDatasetTool;
import com.jarvis.tools.schema.ToolArgumentDefinition;
import com.jarvis.tools.schema.ToolDefinition;
import com.jarvis.tools.schema.ToolOperationDefinition;
import com.jarvis.tools.schema.ToolRegistry;
import com.jarvis.tools.schema.ToolSafetyLevel;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression coverage for the core architectural fix: the native tool loop must be genuinely
 * re-entrant. A model returning TOOL_REQUEST as text instead of a real native tool call, or trying
 * to present an in-progress Store Audit dataset as a finished schedule, must never end the turn -
 * Core has to push the model back into real tool work and keep going until the workflow's own
 * completion validator agrees it is actually done, bounded so a persistently confused model can
 * never spin forever.
 */
class NativeToolLoopServiceReentrantWorkflowTest {

    private static final String MISPLACED_TOOL_REQUEST =
            "{\"type\":\"TOOL_REQUEST\",\"goal\":\"Geocode the extracted store addresses\",\"reason\":\"Need coordinates.\"}";
    // A fixed "today" (a Monday) so SUBMIT_SCHEDULE's date validation is fully deterministic.
    private static final Instant FIXED_NOW = Instant.parse("2026-06-01T10:00:00Z");

    // The exact reported class of bug: extraction -> dataset -> the model writes a TOOL_REQUEST
    // envelope as plain text instead of calling a tool -> a premature "done" answer while the
    // dataset is only geolocated, not scheduled. Every one of those must be recovered from within
    // the loop, and only the genuine final answer (after SUBMIT_SCHEDULE is accepted) must win.
    @Test
    void theLoopRecoversFromATextToolRequestAndAnIncompleteWorkflowBeforeAcceptingTheFinalAnswer() {
        StoreAuditDatasetService datasetService = new StoreAuditDatasetService(new NoopCognitiveEventBus(),
                Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
        StoreDatasetTool storeDatasetTool = new StoreDatasetTool(datasetService);
        // In production, ToolCallingStage.execute() registers the current message's real
        // attachment ids (and owning conversation) before the tool loop runs at all - this test
        // calls NativeToolLoopService directly, so it must do the same registration itself for
        // CREATE_DATASET to record the right conversationId and be findable afterward.
        datasetService.registerAttachments("request-1", "conversation-1", List.of());

        Deque<ModelResponse> turns = new ArrayDeque<>();
        turns.add(toolCallTurn("knowledge__read_document", Map.of("path", "Work/Scheduling/StoreAuditScheduleWorkflow.md")));
        turns.add(toolCallTurn("system__notify_user", Map.of("message", "Odczytalem sklepy, przystepuje do geolokalizacji.")));
        turns.add(toolCallTurn("storedataset__create_dataset", Map.of(
                "sourceImageCount", 1, "sourceAttachmentIds", List.of(),
                "records", List.of(
                        Map.of("network", "Siec", "fullAddress", "Adres 1", "sourceRow", 1),
                        Map.of("network", "Siec", "fullAddress", "Adres 2", "sourceRow", 2),
                        Map.of("network", "Siec", "fullAddress", "Adres 3", "sourceRow", 3)
                ))));
        turns.add(textTurn(MISPLACED_TOOL_REQUEST)); // written as text instead of a real tool call
        // GEOCODE_DATASET requires the dataset to be LOCKED first - verify every record (by
        // recordIndex, the preferred model-facing reference) before geolocation is even attempted.
        turns.add(toolCallTurn("storedataset__verify_dataset", Map.of("verifications", List.of(
                Map.of("recordIndex", 1, "status", "VERIFIED"),
                Map.of("recordIndex", 2, "status", "VERIFIED"),
                Map.of("recordIndex", 3, "status", "VERIFIED")))));
        // No datasetId - Core auto-targets the active canonical Store Audit dataset.
        turns.add(toolCallTurn("location__geocode_dataset", Map.of()));
        turns.add(toolCallTurn("storedataset__set_preferences", Map.of(
                "year", 2026, "month", 6, "preferredDaysOfWeek", List.of("TUESDAY", "WEDNESDAY"))));
        turns.add(textTurn("Oto harmonogram wizyt."));  // premature - dataset not yet scheduled
        turns.add(toolCallTurn("storedataset__submit_schedule", Map.of()));
        turns.add(textTurn("Oto ostateczny harmonogram wizyt."));

        ScriptedProvider provider = new ScriptedProvider(turns);
        FullWorkflowToolManager toolManager = new FullWorkflowToolManager(datasetService, storeDatasetTool);

        NativeToolLoopService service = new NativeToolLoopService(
                List.of(provider), toolManager, query -> ToolIntent.LOCATION,
                new ToolRuntimeProperties(true, 20, 20, 5, 60, "native", 10),
                new NoopCognitiveEventBus(), new ToolRuntimeDebugService(), new ObjectMapper(),
                new NativeToolSchemaMapper(fullRegistry()),
                datasetService
        );

        ToolCallingResult result = service.execute(new ToolCallingRequest(
                "request-1", "conversation-1", "przygotuj harmonogram wizyt",
                "Prepare the audit visit schedule.", "test", "Base prompt",
                new Brain(BrainType.FAST, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.LOW),
                KnowledgeMode.FAST
        ));

        assertThat(result.handled()).isTrue();
        assertThat(result.finalAnswer()).isEqualTo("Oto ostateczny harmonogram wizyt.");
        assertThat(result.finalAnswer()).doesNotContain("TOOL_REQUEST");

        Optional<StoreAuditDataset> dataset = datasetService.findLatestForConversation("conversation-1");
        assertThat(dataset).isPresent();
        assertThat(dataset.get().stage()).isEqualTo(DatasetStage.SCHEDULED);
        assertThat(dataset.get().stores()).hasSize(3);
        assertThat(dataset.get().schedule()).isNotEmpty();

        // The premature "Oto harmonogram wizyt." (turn 6, before SUBMIT_SCHEDULE) must never have
        // been accepted as the final answer.
        assertThat(result.finalAnswer()).isNotEqualTo("Oto harmonogram wizyt.");
    }

    // Regression for the follow-up reported bug: a single CREATE_DATASET call with a large record
    // array can fail outright (model emits an empty records array despite intending to fill it).
    // The incremental START_DATASET/APPEND_RECORDS/FINALIZE_DATASET path must integrate with the
    // same re-entrant loop and completion gate exactly like the one-shot CREATE_DATASET path does.
    @Test
    void theLoopCompletesAWorkflowBuiltIncrementallyViaStartAppendFinalize() {
        StoreAuditDatasetService datasetService = new StoreAuditDatasetService(new NoopCognitiveEventBus(),
                Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
        StoreDatasetTool storeDatasetTool = new StoreDatasetTool(datasetService);
        datasetService.registerAttachments("request-1", "conversation-1", List.of());

        Deque<ModelResponse> turns = new ArrayDeque<>();
        turns.add(toolCallTurn("storedataset__start_dataset", Map.of(
                "sourceImageCount", 1, "sourceAttachmentIds", List.of(),
                "records", List.of(
                        Map.of("network", "Siec", "fullAddress", "Adres 1", "sourceRow", 1),
                        Map.of("network", "Siec", "fullAddress", "Adres 2", "sourceRow", 2)
                ))));
        // No datasetId on any of these - Core auto-targets the active canonical Store Audit dataset.
        turns.add(toolCallTurn("storedataset__append_records", Map.of(
                "records", List.of(Map.of("network", "Siec", "fullAddress", "Adres 3", "sourceRow", 3)))));
        turns.add(toolCallTurn("storedataset__finalize_dataset", Map.of()));
        // GEOCODE_DATASET requires both a LOCKED dataset and the required workflow document read
        // first - verify every record (by recordIndex, the preferred model-facing reference), then
        // read the document, before geolocation is even attempted.
        turns.add(toolCallTurn("storedataset__verify_dataset", Map.of("verifications", List.of(
                Map.of("recordIndex", 1, "status", "VERIFIED"),
                Map.of("recordIndex", 2, "status", "VERIFIED"),
                Map.of("recordIndex", 3, "status", "VERIFIED")))));
        turns.add(toolCallTurn("knowledge__read_document", Map.of("path", "Work/Scheduling/StoreAuditScheduleWorkflow.md")));
        turns.add(toolCallTurn("location__geocode_dataset", Map.of()));
        turns.add(toolCallTurn("storedataset__set_preferences", Map.of(
                "year", 2026, "month", 6, "preferredDaysOfWeek", List.of("TUESDAY", "WEDNESDAY"))));
        turns.add(toolCallTurn("storedataset__submit_schedule", Map.of()));
        turns.add(textTurn("Oto harmonogram wizyt zbudowany przyrostowo."));

        ScriptedProvider provider = new ScriptedProvider(turns);
        FullWorkflowToolManager toolManager = new FullWorkflowToolManager(datasetService, storeDatasetTool);

        NativeToolLoopService service = new NativeToolLoopService(
                List.of(provider), toolManager, query -> ToolIntent.LOCATION,
                new ToolRuntimeProperties(true, 20, 20, 5, 60, "native", 10),
                new NoopCognitiveEventBus(), new ToolRuntimeDebugService(), new ObjectMapper(),
                new NativeToolSchemaMapper(incrementalRegistry()),
                datasetService
        );

        ToolCallingResult result = service.execute(new ToolCallingRequest(
                "request-1", "conversation-1", "przygotuj harmonogram wizyt",
                "Prepare the audit visit schedule incrementally.", "test", "Base prompt",
                new Brain(BrainType.FAST, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.LOW),
                KnowledgeMode.FAST
        ));

        assertThat(result.handled()).isTrue();
        assertThat(result.finalAnswer()).isEqualTo("Oto harmonogram wizyt zbudowany przyrostowo.");

        StoreAuditDataset dataset = datasetService.findLatestForConversation("conversation-1").orElseThrow();
        assertThat(dataset.stage()).isEqualTo(DatasetStage.SCHEDULED);
        assertThat(dataset.stores()).hasSize(3);
    }

    // A model that keeps writing the same TOOL_REQUEST-shaped text instead of ever making a real
    // tool call must not be nagged forever - after the bounded number of corrective retries, the
    // loop accepts the text as final content rather than hanging.
    @Test
    void repeatedTextToolRequestsAreBoundedAndTheLoopEventuallyTerminates() {
        Deque<ModelResponse> turns = new ArrayDeque<>();
        for (int i = 0; i < 5; i++) {
            turns.add(textTurn(MISPLACED_TOOL_REQUEST));
        }
        ScriptedProvider provider = new ScriptedProvider(turns);
        FakeSimpleToolManager toolManager = new FakeSimpleToolManager();

        NativeToolLoopService service = new NativeToolLoopService(
                List.of(provider), toolManager, query -> ToolIntent.NO_TOOL,
                new ToolRuntimeProperties(true, 10, 10, 2, 30, "native", 10),
                new NoopCognitiveEventBus(), new ToolRuntimeDebugService(), new ObjectMapper(),
                new NativeToolSchemaMapper(knowledgeOnlyRegistry()),
                new StoreAuditDatasetService(new NoopCognitiveEventBus())
        );

        ToolCallingResult result = service.execute(new ToolCallingRequest(
                "request-1", "conversation-1", "test",
                "test goal", "test", "Base prompt",
                new Brain(BrainType.FAST, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.LOW),
                KnowledgeMode.FAST
        ));

        assertThat(result.handled()).isTrue();
        // Terminated (not hung), and the loop only ever consumed a bounded number of scripted
        // turns before giving up and accepting the text as final content.
        assertThat(provider.callCount()).isLessThanOrEqualTo(4);
    }

    // A model that briefly returns neither a tool call nor any text content (a transient model
    // hiccup on a large multimodal/many-tool prompt) must get a bounded chance to recover instead
    // of the whole task failing on the very first empty turn.
    @Test
    void theLoopRecoversFromATransientEmptyModelResponseBeforeTheModelFinallyAnswers() {
        Deque<ModelResponse> turns = new ArrayDeque<>();
        turns.add(emptyTurn());
        turns.add(emptyTurn());
        turns.add(textTurn("Oto odpowiedz po odzyskaniu."));
        ScriptedProvider provider = new ScriptedProvider(turns);
        FakeSimpleToolManager toolManager = new FakeSimpleToolManager();

        NativeToolLoopService service = new NativeToolLoopService(
                List.of(provider), toolManager, query -> ToolIntent.NO_TOOL,
                new ToolRuntimeProperties(true, 10, 10, 2, 30, "native", 10),
                new NoopCognitiveEventBus(), new ToolRuntimeDebugService(), new ObjectMapper(),
                new NativeToolSchemaMapper(knowledgeOnlyRegistry()),
                new StoreAuditDatasetService(new NoopCognitiveEventBus())
        );

        ToolCallingResult result = service.execute(new ToolCallingRequest(
                "request-1", "conversation-1", "test",
                "test goal", "test", "Base prompt",
                new Brain(BrainType.FAST, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.LOW),
                KnowledgeMode.FAST
        ));

        assertThat(result.handled()).isTrue();
        assertThat(result.finalAnswer()).isEqualTo("Oto odpowiedz po odzyskaniu.");
    }

    // If the model never recovers, the loop must still terminate with a controlled, honest
    // failure rather than hanging or throwing.
    @Test
    void repeatedEmptyModelResponsesAreBoundedAndTheLoopTerminatesWithAControlledFailure() {
        Deque<ModelResponse> turns = new ArrayDeque<>();
        for (int i = 0; i < 5; i++) {
            turns.add(emptyTurn());
        }
        ScriptedProvider provider = new ScriptedProvider(turns);
        FakeSimpleToolManager toolManager = new FakeSimpleToolManager();

        NativeToolLoopService service = new NativeToolLoopService(
                List.of(provider), toolManager, query -> ToolIntent.NO_TOOL,
                new ToolRuntimeProperties(true, 10, 10, 2, 30, "native", 10),
                new NoopCognitiveEventBus(), new ToolRuntimeDebugService(), new ObjectMapper(),
                new NativeToolSchemaMapper(knowledgeOnlyRegistry()),
                new StoreAuditDatasetService(new NoopCognitiveEventBus())
        );

        ToolCallingResult result = service.execute(new ToolCallingRequest(
                "request-1", "conversation-1", "test",
                "test goal", "test", "Base prompt",
                new Brain(BrainType.FAST, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.LOW),
                KnowledgeMode.FAST
        ));

        assertThat(result.handled()).isTrue();
        assertThat(result.finalAnswer()).contains("EMPTY_MODEL_RESPONSE_WITHOUT_TOOL_CALL");
        // Bounded: at most 1 initial + MAX_EMPTY_RESPONSE_RETRIES(2) retries were consumed.
        assertThat(provider.callCount()).isLessThanOrEqualTo(3);
    }

    private static ModelResponse emptyTurn() {
        return new ModelResponse("", "", List.of(), "stop", new ModelUsage(0, 0, 0));
    }

    // A model that never stops requesting distinct tool calls must still be bounded by the outer
    // step budget, never spin forever.
    @Test
    void anEndlessStreamOfDistinctToolCallsIsBoundedByTheStepBudget() {
        EndlessToolCallProvider provider = new EndlessToolCallProvider();
        FakeSimpleToolManager toolManager = new FakeSimpleToolManager();

        NativeToolLoopService service = new NativeToolLoopService(
                List.of(provider), toolManager, query -> ToolIntent.NO_TOOL,
                new ToolRuntimeProperties(true, 6, 6, 5, 30, "native", 20),
                new NoopCognitiveEventBus(), new ToolRuntimeDebugService(), new ObjectMapper(),
                new NativeToolSchemaMapper(knowledgeOnlyRegistry()),
                new StoreAuditDatasetService(new NoopCognitiveEventBus())
        );

        ToolCallingResult result = service.execute(new ToolCallingRequest(
                "request-1", "conversation-1", "test",
                "test goal", "test", "Base prompt",
                new Brain(BrainType.FAST, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.LOW),
                KnowledgeMode.FAST
        ));

        assertThat(result.handled()).isTrue();
        assertThat(provider.callCount()).isLessThanOrEqualTo(6);
    }

    private static ModelResponse toolCallTurn(String name, Map<String, Object> arguments) {
        return new ModelResponse("", "", List.of(new ModelToolCall("call-" + System.nanoTime(), name, arguments)),
                "tool_calls", new ModelUsage(0, 0, 0));
    }

    private static ModelResponse textTurn(String content) {
        return new ModelResponse(content, "", List.of(), "stop", new ModelUsage(0, 0, 0));
    }

    private static ToolRegistry fullRegistry() {
        ToolDefinition knowledge = new ToolDefinition("knowledge", "Knowledge workspace.", List.of(
                new ToolOperationDefinition("READ_DOCUMENT", "Read a document.", List.of(
                        new ToolArgumentDefinition("path", "string", true, "Path")
                ), false, ToolSafetyLevel.READ)
        ));
        ToolDefinition system = new ToolDefinition("system", "System.", List.of(
                new ToolOperationDefinition("NOTIFY_USER", "Notify user.", List.of(
                        new ToolArgumentDefinition("message", "string", true, "Message")
                ), false, ToolSafetyLevel.READ)
        ));
        ToolDefinition location = new ToolDefinition("location", "Geocoding.", List.of(
                new ToolOperationDefinition("GEOCODE_DATASET", "Geocode dataset records.", List.of(
                        new ToolArgumentDefinition("datasetId", "string", true, "Dataset id")
                ), true, ToolSafetyLevel.WRITE)
        ));
        ToolDefinition storeDataset = new ToolDefinition("storedataset", "Canonical dataset.", List.of(
                new ToolOperationDefinition("CREATE_DATASET", "Create dataset.", List.of(
                        new ToolArgumentDefinition("sourceImageCount", "number", true, "Count"),
                        new ToolArgumentDefinition("sourceAttachmentIds", "array", true, "Ids"),
                        new ToolArgumentDefinition("records", "array", true, "Records")
                ), true, ToolSafetyLevel.WRITE),
                new ToolOperationDefinition("VERIFY_DATASET", "Verify dataset.", List.of(
                        new ToolArgumentDefinition("datasetId", "string", true, "Dataset id"),
                        new ToolArgumentDefinition("verifications", "array", true, "Verifications")
                ), true, ToolSafetyLevel.WRITE),
                new ToolOperationDefinition("SET_PREFERENCES", "Set scheduling preferences.", List.of(
                        new ToolArgumentDefinition("datasetId", "string", false, "Dataset id"),
                        new ToolArgumentDefinition("year", "number", false, "Year"),
                        new ToolArgumentDefinition("month", "number", true, "Month"),
                        new ToolArgumentDefinition("preferredDaysOfWeek", "array", false, "Preferred days")
                ), true, ToolSafetyLevel.WRITE),
                new ToolOperationDefinition("SUBMIT_SCHEDULE", "Submit schedule.", List.of(
                        new ToolArgumentDefinition("datasetId", "string", true, "Dataset id"),
                        new ToolArgumentDefinition("days", "array", true, "Days")
                ), true, ToolSafetyLevel.WRITE)
        ));
        return registryOf(knowledge, system, location, storeDataset);
    }

    private static ToolRegistry incrementalRegistry() {
        ToolDefinition knowledge = new ToolDefinition("knowledge", "Knowledge workspace.", List.of(
                new ToolOperationDefinition("READ_DOCUMENT", "Read a document.", List.of(
                        new ToolArgumentDefinition("path", "string", true, "Path")
                ), false, ToolSafetyLevel.READ)
        ));
        ToolDefinition location = new ToolDefinition("location", "Geocoding.", List.of(
                new ToolOperationDefinition("GEOCODE_DATASET", "Geocode dataset records.", List.of(
                        new ToolArgumentDefinition("datasetId", "string", true, "Dataset id")
                ), true, ToolSafetyLevel.WRITE)
        ));
        ToolDefinition storeDataset = new ToolDefinition("storedataset", "Canonical dataset.", List.of(
                new ToolOperationDefinition("START_DATASET", "Start dataset.", List.of(
                        new ToolArgumentDefinition("sourceImageCount", "number", true, "Count"),
                        new ToolArgumentDefinition("sourceAttachmentIds", "array", true, "Ids"),
                        new ToolArgumentDefinition("records", "array", true, "Records")
                ), true, ToolSafetyLevel.WRITE),
                new ToolOperationDefinition("APPEND_RECORDS", "Append records.", List.of(
                        new ToolArgumentDefinition("datasetId", "string", true, "Dataset id"),
                        new ToolArgumentDefinition("records", "array", true, "Records")
                ), true, ToolSafetyLevel.WRITE),
                new ToolOperationDefinition("FINALIZE_DATASET", "Finalize dataset.", List.of(
                        new ToolArgumentDefinition("datasetId", "string", true, "Dataset id")
                ), true, ToolSafetyLevel.WRITE),
                new ToolOperationDefinition("VERIFY_DATASET", "Verify dataset.", List.of(
                        new ToolArgumentDefinition("datasetId", "string", true, "Dataset id"),
                        new ToolArgumentDefinition("verifications", "array", true, "Verifications")
                ), true, ToolSafetyLevel.WRITE),
                new ToolOperationDefinition("SET_PREFERENCES", "Set scheduling preferences.", List.of(
                        new ToolArgumentDefinition("datasetId", "string", false, "Dataset id"),
                        new ToolArgumentDefinition("year", "number", false, "Year"),
                        new ToolArgumentDefinition("month", "number", true, "Month"),
                        new ToolArgumentDefinition("preferredDaysOfWeek", "array", false, "Preferred days")
                ), true, ToolSafetyLevel.WRITE),
                new ToolOperationDefinition("SUBMIT_SCHEDULE", "Submit schedule.", List.of(
                        new ToolArgumentDefinition("datasetId", "string", true, "Dataset id"),
                        new ToolArgumentDefinition("days", "array", true, "Days")
                ), true, ToolSafetyLevel.WRITE)
        ));
        return registryOf(knowledge, location, storeDataset);
    }

    private static ToolRegistry knowledgeOnlyRegistry() {
        ToolDefinition knowledge = new ToolDefinition("knowledge", "Knowledge workspace.", List.of(
                new ToolOperationDefinition("READ_DOCUMENT", "Read a document.", List.of(
                        new ToolArgumentDefinition("path", "string", true, "Path")
                ), false, ToolSafetyLevel.READ)
        ));
        return registryOf(knowledge);
    }

    private static ToolRegistry registryOf(ToolDefinition... definitions) {
        List<ToolDefinition> list = List.of(definitions);
        return new ToolRegistry() {
            @Override
            public List<ToolDefinition> definitions() {
                return list;
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
     * Never stops proposing a distinct, freshly-argumented native tool call - proves the outer
     * step budget (not the model's own good behavior) is what eventually ends the loop.
     */
    private static final class EndlessToolCallProvider implements AIProvider {

        private int calls;

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
            return toolCallTurn("knowledge__read_document", Map.of("path", "doc-" + calls + ".md"));
        }
    }

    /**
     * Delegates {@code storedataset} calls to a real {@link StoreDatasetTool}, resolves the
     * {@code "PLACEHOLDER"} dataset id argument used by the scripted turns above to whatever id the
     * dataset actually got assigned, and geocodes every record for real via {@link
     * StoreAuditDatasetService#updateGeolocation} so the completion gate sees genuine stage
     * progression exactly like production would.
     */
    private static final class FullWorkflowToolManager implements ToolManager {

        private final StoreAuditDatasetService datasetService;
        private final StoreDatasetTool storeDatasetTool;

        private FullWorkflowToolManager(StoreAuditDatasetService datasetService, StoreDatasetTool storeDatasetTool) {
            this.datasetService = datasetService;
            this.storeDatasetTool = storeDatasetTool;
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
            String tool = request.toolName();
            String operation = request.operation();
            if ("knowledge".equalsIgnoreCase(tool) && "READ_DOCUMENT".equalsIgnoreCase(operation)) {
                return new ToolResult(true, "knowledge", "READ_DOCUMENT", request.requestId(), request.conversationId(),
                        false, List.of(), "Document read", Map.of("content", "Workflow procedure text."), "", "", false, "");
            }
            if ("system".equalsIgnoreCase(tool) && "NOTIFY_USER".equalsIgnoreCase(operation)) {
                return new ToolResult(true, "system", "NOTIFY_USER", request.requestId(), request.conversationId(),
                        false, List.of(), "Notified", Map.of(), "", "", false, "");
            }
            if ("storedataset".equalsIgnoreCase(tool)) {
                return storeDatasetTool.execute(resolvePlaceholder(request));
            }
            if ("location".equalsIgnoreCase(tool) && "GEOCODE_DATASET".equalsIgnoreCase(operation)) {
                String datasetId = resolveDatasetId(request);
                StoreAuditDataset dataset = datasetService.getDataset(datasetId).orElseThrow();
                List<GeolocationEntry> entries = dataset.stores().stream()
                        .map(record -> new GeolocationEntry(record.id(), GeolocationStatus.RESOLVED, 52.0, 21.0))
                        .toList();
                datasetService.updateGeolocation(datasetId, entries);
                return new ToolResult(true, "location", "GEOCODE_DATASET", request.requestId(), request.conversationId(),
                        true, List.of(datasetId), "Geocoded dataset records", Map.of("datasetId", datasetId), "", "", false, "");
            }
            throw new UnsupportedOperationException("Unexpected tool call: " + tool + "." + operation);
        }

        /**
         * Auto-fills SUBMIT_SCHEDULE's {@code days} grouping from the real canonical dataset's
         * current record ids - datasetId itself is no longer a placeholder to resolve here at all,
         * since {@link NativeToolLoopService} already auto-targets/injects the active canonical
         * dataset's real id before this tool manager ever sees the request.
         */
        private ToolRequest resolvePlaceholder(ToolRequest request) {
            if (!"SUBMIT_SCHEDULE".equalsIgnoreCase(request.operation()) || request.arguments().containsKey("days")) {
                return request;
            }
            String datasetId = String.valueOf(request.arguments().get("datasetId"));
            StoreAuditDataset dataset = datasetService.getDataset(datasetId).orElseThrow();
            List<String> ids = dataset.stores().stream().map(record -> record.id()).toList();
            java.util.Map<String, Object> arguments = new java.util.HashMap<>(request.arguments());
            arguments.put("days", List.of(Map.of("day", 1, "date", "2026-06-02", "storeIds", ids,
                    "routeDistanceMeters", 10000d, "routeDurationSeconds", 1200d, "auditDurationSeconds", 1800d)));
            return new ToolRequest(request.toolName(), request.operation(), request.conversationId(), request.requestId(),
                    request.reason(), request.reasoningSummary(), arguments);
        }

        private String resolveDatasetId(ToolRequest request) {
            return datasetService.findLatestForConversation(request.conversationId())
                    .orElseThrow(() -> new IllegalStateException("No dataset yet for conversation " + request.conversationId()))
                    .datasetId();
        }
    }

    private static final class FakeSimpleToolManager implements ToolManager {

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
            return new ToolResult(true, request.toolName(), request.operation(), request.requestId(), request.conversationId(),
                    false, List.of(), "Document read", Map.of("content", "..."), "", "", false, "");
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
