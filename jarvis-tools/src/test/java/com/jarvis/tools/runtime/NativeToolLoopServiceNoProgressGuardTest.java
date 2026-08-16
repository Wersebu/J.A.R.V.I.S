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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for the generic, argument-agnostic "no progress" guard in
 * {@link NativeToolLoopService}: a model repeatedly rewording the same query (never hitting the
 * exact-fingerprint duplicate blocker because the arguments keep changing slightly) must still be
 * stopped once it repeats the same tool+operation too many times without new information -
 * mirroring the "Nowa Wola 05-500 wspolrzedne geograficzne" / "... Google Maps" repeated-rewording
 * pattern from the reported bug, without being tied to that specific text.
 */
class NativeToolLoopServiceNoProgressGuardTest {

    @Test
    void repeatedRewordedSearchWebCallsAreBlockedAfterTheConfiguredThreshold() {
        Deque<ModelResponse> turns = new ArrayDeque<>();
        for (int i = 1; i <= 7; i++) {
            turns.add(toolCallTurn("web__search_web", Map.of("query", "Nowa Wola 05-500 wersja zapytania " + i)));
        }
        turns.add(textTurn("Nie udalo mi sie znalezc dodatkowych informacji."));
        ScriptedProvider provider = new ScriptedProvider(turns);

        FakeToolManager toolManager = new FakeToolManager(emptySearchResult());

        NativeToolLoopService service = new NativeToolLoopService(
                List.of(provider), toolManager, query -> ToolIntent.LOCATION,
                new ToolRuntimeProperties(true, 10, 10, 2, 30, "native", 5),
                new NoopCognitiveEventBus(), new ToolRuntimeDebugService(), new ObjectMapper(),
                new NativeToolSchemaMapper(webRegistry())
        );

        ToolCallingResult result = service.execute(new ToolCallingRequest(
                "request-1", "conversation-1",
                "Znajdz wspolrzedne Nowa Wola 05-500.",
                "Geocode the starting point.",
                "test",
                "Base prompt",
                new Brain(BrainType.FAST, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.LOW),
                KnowledgeMode.FAST
        ));

        assertThat(result.handled()).isTrue();
        // First 5 rewordings execute for real; the 6th and 7th are blocked as no-progress.
        assertThat(toolManager.executedCount()).isEqualTo(5);
        assertThat(result.steps()).filteredOn(step -> "NO_PROGRESS_BLOCKED".equals(step.action())).hasSize(2);
        assertThat(result.results()).filteredOn(r -> "NO_PROGRESS_OPERATION_REPEATED".equals(r.errorCode())).hasSize(2);
    }

    @Test
    void differentAddressLookupsUnderTheThresholdAreNeverBlocked() {
        Deque<ModelResponse> turns = new ArrayDeque<>();
        turns.add(toolCallTurn("web__search_web", Map.of("query", "adres A wspolrzedne")));
        turns.add(toolCallTurn("web__search_web", Map.of("query", "adres B wspolrzedne")));
        turns.add(toolCallTurn("web__search_web", Map.of("query", "adres C wspolrzedne")));
        turns.add(textTurn("Znalazlem wspolrzedne wszystkich adresow."));
        ScriptedProvider provider = new ScriptedProvider(turns);

        FakeToolManager toolManager = new FakeToolManager(emptySearchResult());

        NativeToolLoopService service = new NativeToolLoopService(
                List.of(provider), toolManager, query -> ToolIntent.LOCATION,
                new ToolRuntimeProperties(true, 10, 10, 2, 30, "native", 5),
                new NoopCognitiveEventBus(), new ToolRuntimeDebugService(), new ObjectMapper(),
                new NativeToolSchemaMapper(webRegistry())
        );

        ToolCallingResult result = service.execute(new ToolCallingRequest(
                "request-2", "conversation-1", "Znajdz wspolrzedne trzech adresow.",
                "Geocode three addresses.", "test",
                "Base prompt", new Brain(BrainType.FAST, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.LOW),
                KnowledgeMode.FAST
        ));

        assertThat(result.handled()).isTrue();
        assertThat(toolManager.executedCount()).isEqualTo(3);
        assertThat(result.steps()).noneMatch(step -> "NO_PROGRESS_BLOCKED".equals(step.action()));
    }

    private static ModelResponse toolCallTurn(String name, Map<String, Object> arguments) {
        return new ModelResponse("", "", List.of(new ModelToolCall("call-" + System.nanoTime(), name, arguments)),
                "tool_calls", new ModelUsage(0, 0, 0));
    }

    private static ModelResponse textTurn(String content) {
        return new ModelResponse(content, "", List.of(), "stop", new ModelUsage(0, 0, 0));
    }

    private static ToolResult emptySearchResult() {
        return new ToolResult(true, "web", "SEARCH_WEB", "", "", false, List.of(), "Web search finished",
                Map.of("results", List.of()), "", "", false, "");
    }

    private static ToolRegistry webRegistry() {
        ToolDefinition definition = new ToolDefinition("web", "Web search", List.of(
                new ToolOperationDefinition("SEARCH_WEB", "Search web", List.of(
                        new ToolArgumentDefinition("query", "string", true, "Search query")
                ), false, ToolSafetyLevel.READ)
        ));
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

        private final ToolResult scriptedResult;
        private int executedCount;

        private FakeToolManager(ToolResult scriptedResult) {
            this.scriptedResult = scriptedResult;
        }

        int executedCount() {
            return executedCount;
        }

        @Override
        public List<JarvisTool> listTools() {
            return List.of();
        }

        @Override
        public Optional<JarvisTool> findTool(String name) {
            return "web".equalsIgnoreCase(name) ? Optional.of(new PlaceholderWebTool()) : Optional.empty();
        }

        @Override
        public ToolResult execute(ToolRequest request) {
            executedCount++;
            return scriptedResult;
        }
    }

    private static final class PlaceholderWebTool implements JarvisTool {

        @Override
        public String getName() {
            return "web";
        }

        @Override
        public String getDescription() {
            return "placeholder";
        }

        @Override
        public ToolResult execute(ToolRequest request) {
            throw new UnsupportedOperationException("Not used in this test");
        }
    }

    private static final class NoopCognitiveEventBus implements CognitiveEventBus {

        @Override
        public void startRequest(String requestId, String conversationId, java.util.function.Consumer<com.jarvis.common.event.CognitiveEvent> sink) {
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
