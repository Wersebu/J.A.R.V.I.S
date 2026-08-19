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
 * Regression test for the deterministic completion gate: confident text is not enough when the
 * only successful evidence is bootstrap/discovery and the user asked for concrete project data.
 */
class NativeToolLoopServiceConfidentWrongAnswerLimitationTest {

    private static final String LIST_STUDIOS = "mcp_roblox_list_roblox_studios__call";

    @Test
    void confidentButFabricatedAnswerFromBootstrapOnlyEvidenceIsBlocked() {
        Deque<ModelResponse> turns = new ArrayDeque<>();
        turns.add(toolCallTurn(LIST_STUDIOS, Map.of()));
        // No hedging, no admission of insufficiency - a specific, confident, but fabricated answer.
        // list_roblox_studios cannot possibly have returned a folder name; the model invented one.
        turns.add(textTurn("Dostepny folder w projekcie to: Projekt bez tytulu."));
        ScriptedProvider provider = new ScriptedProvider(turns);
        NativeToolLoopService service = newService(provider);

        ToolCallingResult result = service.execute(new ToolCallingRequest(
                "request-1", "conversation-1", "list the folders in my Roblox project",
                "Explore the Roblox Studio project tree.", "test", "Base prompt",
                new Brain(BrainType.FAST, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.LOW),
                KnowledgeMode.FAST
        ));

        assertThat(result.finalAnswer()).contains("concrete information from a target system");
        assertThat(result.finalAnswer()).doesNotContain("Projekt bez tytulu");
        assertThat(provider.callCount()).isGreaterThan(2);
    }

    private NativeToolLoopService newService(ScriptedProvider provider) {
        StoreAuditDatasetService datasetService = new StoreAuditDatasetService(new NoopCognitiveEventBus());
        return new NativeToolLoopService(
                List.of(provider), new FakeRobloxToolManager(), query -> ToolIntent.SEARCH_WEB,
                new ToolRuntimeProperties(true, 15, 15, 2, 30, "native", 10, 20),
                new NoopCognitiveEventBus(), new ToolRuntimeDebugService(), new ObjectMapper(),
                new NativeToolSchemaMapper(robloxRegistry()), datasetService
        );
    }

    private static ToolRegistry robloxRegistry() {
        ToolDefinition listStudios = new ToolDefinition("mcp_roblox_list_roblox_studios", "MCP tool.", List.of(
                new ToolOperationDefinition("CALL", "Call MCP tool.", List.<ToolArgumentDefinition>of(), false, ToolSafetyLevel.READ)
        ));
        List<ToolDefinition> definitions = List.of(listStudios);
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

    private static ModelResponse toolCallTurn(String name, Map<String, Object> arguments) {
        return new ModelResponse("", "", List.of(new ModelToolCall("call-" + System.nanoTime(), name, arguments)),
                "tool_calls", new ModelUsage(0, 0, 0));
    }

    private static ModelResponse textTurn(String content) {
        return new ModelResponse(content, "", List.of(), "stop", new ModelUsage(0, 0, 0));
    }

    private static final class FakeRobloxToolManager implements ToolManager {

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
                    false, List.of(), "MCP tool completed.", Map.of(), "", "", false, "");
        }
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
