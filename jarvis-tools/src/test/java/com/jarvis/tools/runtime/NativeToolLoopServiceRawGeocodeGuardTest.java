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
import com.jarvis.tools.dataset.StoreAuditDatasetService;
import com.jarvis.tools.schema.ToolArgumentDefinition;
import com.jarvis.tools.schema.ToolDefinition;
import com.jarvis.tools.schema.ToolOperationDefinition;
import com.jarvis.tools.schema.ToolRegistry;
import com.jarvis.tools.schema.ToolSafetyLevel;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for the exact reported bug: a vision model correctly extracted 23 store
 * addresses, then instead of locking them into a {@code storeDataset}, geocoded a handful of them
 * one at a time via plain {@code location.GEOCODE}, ran out of turns, and presented a "schedule"
 * covering only 2 of the 23 stores - with nothing in Core ever checking whether the result covered
 * everything the model had actually extracted. A prompt-only nudge to use {@code storeDataset}
 * first had already been added and was still being ignored twice in production, so this is an
 * enforced guard: raw {@code location.GEOCODE} calls are blocked past a small cumulative address
 * count until a {@code storeDataset} exists for the task.
 */
class NativeToolLoopServiceRawGeocodeGuardTest {

    @Test
    void rawGeocodeCallsAreBlockedOnceTheCumulativeAddressCountExceedsTheLimitWithoutADataset() {
        Deque<ModelResponse> turns = new ArrayDeque<>();
        for (int i = 1; i <= 6; i++) {
            turns.add(toolCallTurn("location__geocode", Map.of("query", "Sklep " + i + ", 08-400 Garwolin")));
        }
        turns.add(textTurn("Oto grafik."));
        ScriptedProvider provider = new ScriptedProvider(turns);
        FakeToolManager toolManager = new FakeToolManager();

        NativeToolLoopService service = new NativeToolLoopService(
                List.of(provider), toolManager, query -> ToolIntent.LOCATION,
                new ToolRuntimeProperties(true, 10, 10, 2, 30, "native", 10),
                new NoopCognitiveEventBus(), new ToolRuntimeDebugService(), new ObjectMapper(),
                new NativeToolSchemaMapper(registry()),
                new StoreAuditDatasetService(new NoopCognitiveEventBus())
        );

        ToolCallingResult result = service.execute(new ToolCallingRequest(
                "request-1", "conversation-1", "przygotuj grafik na sierpien",
                "Geocode the extracted store addresses.", "test", "Base prompt",
                new Brain(BrainType.FAST, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.LOW),
                KnowledgeMode.FAST
        ));

        assertThat(result.handled()).isTrue();
        // 4 addresses execute for real (the configured limit); the 5th and 6th single-address
        // calls are blocked before ever reaching LocationTool.
        assertThat(toolManager.geocodeCalls()).isEqualTo(4);
        assertThat(result.results()).filteredOn(r -> "RAW_GEOCODE_WITHOUT_DATASET_BLOCKED".equals(r.errorCode())).hasSize(2);
    }

    @Test
    void aSmallBatchUnderTheLimitIsNeverBlocked() {
        Deque<ModelResponse> turns = new ArrayDeque<>();
        turns.add(toolCallTurn("location__geocode", Map.of("queries", List.of("Adres A", "Adres B", "Adres C"))));
        turns.add(textTurn("Oto trasa."));
        ScriptedProvider provider = new ScriptedProvider(turns);
        FakeToolManager toolManager = new FakeToolManager();

        NativeToolLoopService service = new NativeToolLoopService(
                List.of(provider), toolManager, query -> ToolIntent.LOCATION,
                new ToolRuntimeProperties(true, 10, 10, 2, 30, "native", 10),
                new NoopCognitiveEventBus(), new ToolRuntimeDebugService(), new ObjectMapper(),
                new NativeToolSchemaMapper(registry()),
                new StoreAuditDatasetService(new NoopCognitiveEventBus())
        );

        ToolCallingResult result = service.execute(new ToolCallingRequest(
                "request-2", "conversation-1", "Zaplanuj trase do trzech adresow.",
                "Geocode three stops.", "test", "Base prompt",
                new Brain(BrainType.FAST, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.LOW),
                KnowledgeMode.FAST
        ));

        assertThat(result.handled()).isTrue();
        assertThat(toolManager.geocodeCalls()).isEqualTo(1);
        assertThat(result.results()).noneMatch(r -> "RAW_GEOCODE_WITHOUT_DATASET_BLOCKED".equals(r.errorCode()));
    }

    @Test
    void geocodeDatasetIsNeverBlockedRegardlessOfCountOnceADatasetExists() {
        Deque<ModelResponse> turns = new ArrayDeque<>();
        turns.add(toolCallTurn("storedataset__create_dataset", Map.of(
                "sourceImageCount", 1, "sourceAttachmentIds", List.of(),
                "records", List.of(Map.of("network", "Biedronka", "fullAddress", "A", "sourceRow", 1)))));
        for (int i = 1; i <= 6; i++) {
            turns.add(toolCallTurn("location__geocode_dataset", Map.of(
                    "datasetId", "dataset-1", "records", List.of(Map.of("recordId", "store-00" + i, "fullAddress", "Adres " + i)))));
        }
        turns.add(textTurn("Oto grafik."));
        ScriptedProvider provider = new ScriptedProvider(turns);
        FakeToolManager toolManager = new FakeToolManager();

        NativeToolLoopService service = new NativeToolLoopService(
                List.of(provider), toolManager, query -> ToolIntent.LOCATION,
                new ToolRuntimeProperties(true, 10, 10, 2, 30, "native", 10),
                new NoopCognitiveEventBus(), new ToolRuntimeDebugService(), new ObjectMapper(),
                new NativeToolSchemaMapper(registryWithDatasetOperations()),
                new StoreAuditDatasetService(new NoopCognitiveEventBus())
        );

        ToolCallingResult result = service.execute(new ToolCallingRequest(
                "request-3", "conversation-1", "przygotuj grafik na sierpien",
                "Create the dataset then geocode it.", "test", "Base prompt",
                new Brain(BrainType.FAST, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.LOW),
                KnowledgeMode.FAST
        ));

        assertThat(result.handled()).isTrue();
        assertThat(result.results()).noneMatch(r -> "RAW_GEOCODE_WITHOUT_DATASET_BLOCKED".equals(r.errorCode()));
    }

    @Test
    void rawGeocodeAfterCreatingADatasetIsNeverBlocked() {
        Deque<ModelResponse> turns = new ArrayDeque<>();
        turns.add(toolCallTurn("storedataset__create_dataset", Map.of(
                "sourceImageCount", 1, "sourceAttachmentIds", List.of(),
                "records", List.of(Map.of("network", "Biedronka", "fullAddress", "A", "sourceRow", 1)))));
        for (int i = 1; i <= 6; i++) {
            turns.add(toolCallTurn("location__geocode", Map.of("query", "Sklep " + i + ", 08-400 Garwolin")));
        }
        turns.add(textTurn("Oto grafik."));
        ScriptedProvider provider = new ScriptedProvider(turns);
        FakeToolManager toolManager = new FakeToolManager();

        NativeToolLoopService service = new NativeToolLoopService(
                List.of(provider), toolManager, query -> ToolIntent.LOCATION,
                new ToolRuntimeProperties(true, 10, 10, 2, 30, "native", 10),
                new NoopCognitiveEventBus(), new ToolRuntimeDebugService(), new ObjectMapper(),
                new NativeToolSchemaMapper(registryWithDatasetOperations()),
                new StoreAuditDatasetService(new NoopCognitiveEventBus())
        );

        ToolCallingResult result = service.execute(new ToolCallingRequest(
                "request-4", "conversation-1", "przygotuj grafik na sierpien",
                "Create the dataset then geocode.", "test", "Base prompt",
                new Brain(BrainType.FAST, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.LOW),
                KnowledgeMode.FAST
        ));

        assertThat(result.handled()).isTrue();
        assertThat(toolManager.geocodeCalls()).isEqualTo(6);
        assertThat(result.results()).noneMatch(r -> "RAW_GEOCODE_WITHOUT_DATASET_BLOCKED".equals(r.errorCode()));
    }

    private static ModelResponse toolCallTurn(String name, Map<String, Object> arguments) {
        return new ModelResponse("", "", List.of(new ModelToolCall("call-" + System.nanoTime(), name, arguments)),
                "tool_calls", new ModelUsage(0, 0, 0));
    }

    private static ModelResponse textTurn(String content) {
        return new ModelResponse(content, "", List.of(), "stop", new ModelUsage(0, 0, 0));
    }

    private static ToolRegistry registry() {
        ToolDefinition location = new ToolDefinition("location", "Geocoding.", List.of(
                new ToolOperationDefinition("GEOCODE", "Geocode addresses.", List.of(
                        new ToolArgumentDefinition("query", "string", false, "Single address"),
                        new ToolArgumentDefinition("queries", "array", false, "Multiple addresses")
                ), false, ToolSafetyLevel.READ)
        ));
        return simpleRegistry(location);
    }

    private static ToolRegistry registryWithDatasetOperations() {
        ToolDefinition location = new ToolDefinition("location", "Geocoding.", List.of(
                new ToolOperationDefinition("GEOCODE", "Geocode addresses.", List.of(
                        new ToolArgumentDefinition("query", "string", false, "Single address"),
                        new ToolArgumentDefinition("queries", "array", false, "Multiple addresses")
                ), false, ToolSafetyLevel.READ),
                new ToolOperationDefinition("GEOCODE_DATASET", "Geocode dataset records.", List.of(
                        new ToolArgumentDefinition("datasetId", "string", true, "Dataset id"),
                        new ToolArgumentDefinition("records", "array", true, "Records")
                ), true, ToolSafetyLevel.WRITE)
        ));
        ToolDefinition storeDataset = new ToolDefinition("storedataset", "Canonical dataset.", List.of(
                new ToolOperationDefinition("CREATE_DATASET", "Create dataset.", List.of(
                        new ToolArgumentDefinition("sourceImageCount", "number", true, "Count"),
                        new ToolArgumentDefinition("sourceAttachmentIds", "array", true, "Ids"),
                        new ToolArgumentDefinition("records", "array", true, "Records")
                ), true, ToolSafetyLevel.WRITE)
        ));
        return new ToolRegistry() {
            @Override
            public List<ToolDefinition> definitions() {
                return List.of(location, storeDataset);
            }

            @Override
            public String promptSection() {
                return "";
            }
        };
    }

    private static ToolRegistry simpleRegistry(ToolDefinition definition) {
        return new ToolRegistry() {
            @Override
            public List<ToolDefinition> definitions() {
                return List.of(definition);
            }

            @Override
            public String promptSection() {
                return "";
            }
        };
    }

    private static final class ScriptedProvider implements AIProvider {

        private final Deque<ModelResponse> turns;

        private ScriptedProvider(Deque<ModelResponse> turns) {
            this.turns = turns;
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
            return turns.isEmpty() ? textTurn("") : turns.poll();
        }
    }

    private static final class FakeToolManager implements ToolManager {

        private int geocodeCalls;

        int geocodeCalls() {
            return geocodeCalls;
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
            if ("location".equalsIgnoreCase(request.toolName()) && "GEOCODE".equalsIgnoreCase(request.operation())) {
                geocodeCalls++;
                return new ToolResult(true, "location", "GEOCODE", request.requestId(), request.conversationId(),
                        false, List.of(), "Geocoded", Map.of("successfulPoints", List.of()), "", "", false, "");
            }
            if ("location".equalsIgnoreCase(request.toolName()) && "GEOCODE_DATASET".equalsIgnoreCase(request.operation())) {
                return new ToolResult(true, "location", "GEOCODE_DATASET", request.requestId(), request.conversationId(),
                        true, List.of(), "Geocoded dataset records", Map.of(), "", "", false, "");
            }
            if ("storedataset".equalsIgnoreCase(request.toolName()) && "CREATE_DATASET".equalsIgnoreCase(request.operation())) {
                return new ToolResult(true, "storedataset", "CREATE_DATASET", request.requestId(), request.conversationId(),
                        true, List.of("dataset-1"), "Dataset created", Map.of("datasetId", "dataset-1", "count", 1), "", "", false, "");
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
