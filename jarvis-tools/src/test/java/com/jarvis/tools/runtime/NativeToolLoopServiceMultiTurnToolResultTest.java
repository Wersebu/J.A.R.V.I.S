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
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for the confirmed root cause of a real "no user query found in messages" HTTP
 * 500 from Ollama {@code /api/chat} during multi-turn native tool continuation: the {@code
 * role=tool} message JARVIS sent after a tool call carried no {@code tool_name} field at all - a
 * field that does not exist anywhere in the {@code ModelMessage}/{@code OllamaChatMessage} DTO
 * chain before this fix. A direct, isolated test against Ollama with {@code tool_name} present
 * completed multi-turn tool calling correctly with no synthetic "continue" user message needed.
 */
class NativeToolLoopServiceMultiTurnToolResultTest {

    @Test
    void secondTurnOutboundMessagesCarryTheExactToolNameAndCallIdFromTheFirstTurn() {
        Deque<ModelResponse> turns = new ArrayDeque<>();
        turns.add(toolCallTurn("call_abc1", "list_sessions__call", Map.of()));
        turns.add(toolCallTurn("call_def2", "search_tree__call", Map.of("session_id", "studio-123")));
        turns.add(textTurn("Workspace, ReplicatedStorage"));
        CapturingProvider provider = new CapturingProvider(turns);
        FakeToolManager toolManager = new FakeToolManager(Map.of(
                "list_sessions", Map.of("session_id", "studio-123"),
                "search_tree", Map.of("folders", List.of("Workspace", "ReplicatedStorage"))
        ));

        NativeToolLoopService service = newService(provider, toolManager, registry("list_sessions", "search_tree"));
        ToolCallingResult result = service.execute(new ToolCallingRequest(
                "request-1", "conversation-1", "list folder structure",
                "Explore the connected project tree.", "test", "Base prompt",
                new Brain(BrainType.FAST, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.LOW),
                KnowledgeMode.FAST
        ));

        assertThat(result.finalAnswer()).isEqualTo("Workspace, ReplicatedStorage");
        assertThat(provider.callCount()).isEqualTo(3);

        // Turn 2's outbound messages (the exact list handed to toolChat, mirroring what becomes the
        // real Ollama /api/chat body) must contain the tool result message with the SAME tool call
        // id and the EXACT native function name the model itself used in turn 1 - never omitted,
        // never re-derived, never a different representation.
        List<ModelMessage> turn2Messages = provider.messagesAt(1);
        ModelMessage toolResultMessage = turn2Messages.stream()
                .filter(message -> "tool".equals(message.role()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No role=tool message found in turn 2 outbound messages"));
        assertThat(toolResultMessage.toolCallId()).isEqualTo("call_abc1");
        assertThat(toolResultMessage.toolName()).isEqualTo("list_sessions__call");

        // The preceding assistant turn must still carry the exact same tool call id/name too.
        ModelMessage assistantToolCallMessage = turn2Messages.stream()
                .filter(message -> "assistant".equals(message.role()) && !message.toolCalls().isEmpty())
                .findFirst()
                .orElseThrow(() -> new AssertionError("No assistant tool_call message found in turn 2 outbound messages"));
        assertThat(assistantToolCallMessage.toolCalls()).extracting(ModelToolCall::id).contains("call_abc1");
        assertThat(assistantToolCallMessage.toolCalls()).extracting(ModelToolCall::name).contains("list_sessions__call");
    }

    // Regression coverage for the reported Roblox scenario: turn 1 discovers a studio, turn 2's
    // history must show the durable tool_name for that first call, and the model must be free to
    // move on to search_game_tree on its own - Core must never repeat list_roblox_studios itself.
    @Test
    void robloxListStudiosThenSearchGameTreeCarriesTheToolNameAcrossTurnsWithoutCoreRepeatingDiscovery() {
        Deque<ModelResponse> turns = new ArrayDeque<>();
        turns.add(toolCallTurn("call_1", "mcp_roblox_list_roblox_studios__call", Map.of()));
        turns.add(toolCallTurn("call_2", "mcp_roblox_search_game_tree__call", Map.of("query", "Workspace")));
        turns.add(textTurn("Workspace, ReplicatedStorage, ServerScriptService"));
        CapturingProvider provider = new CapturingProvider(turns);
        FakeToolManager toolManager = new FakeToolManager(Map.of(
                "mcp_roblox_list_roblox_studios", Map.of("studio_id", "abc"),
                "mcp_roblox_search_game_tree", Map.of("folders", List.of("Workspace", "ReplicatedStorage", "ServerScriptService"))
        ));

        NativeToolLoopService service = newService(provider, toolManager,
                registry("mcp_roblox_list_roblox_studios", "mcp_roblox_search_game_tree"));
        ToolCallingResult result = service.execute(new ToolCallingRequest(
                "request-1", "conversation-1", "podaj strukture folderow aktualnie podlaczonego projektu roblox",
                "Explore the connected Roblox Studio project tree.", "test", "Base prompt",
                new Brain(BrainType.FAST, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.LOW),
                KnowledgeMode.FAST
        ));

        assertThat(result.finalAnswer()).isEqualTo("Workspace, ReplicatedStorage, ServerScriptService");
        // Exactly 3 provider turns - Core never inserted an extra list_roblox_studios call of its own.
        assertThat(provider.callCount()).isEqualTo(3);
        assertThat(toolManager.executedTools()).containsExactly(
                "mcp_roblox_list_roblox_studios", "mcp_roblox_search_game_tree");

        List<ModelMessage> turn2Messages = provider.messagesAt(1);
        assertThat(turn2Messages)
                .filteredOn(message -> "tool".equals(message.role()))
                .extracting(ModelMessage::toolName)
                .containsExactly("mcp_roblox_list_roblox_studios__call");
    }

    private NativeToolLoopService newService(CapturingProvider provider, ToolManager toolManager, ToolRegistry registry) {
        StoreAuditDatasetService datasetService = new StoreAuditDatasetService(new NoopCognitiveEventBus());
        return new NativeToolLoopService(
                List.of(provider), toolManager, query -> ToolIntent.SEARCH_WEB,
                new ToolRuntimeProperties(true, 15, 15, 2, 30, "native", 10, 20),
                new NoopCognitiveEventBus(), new ToolRuntimeDebugService(), new ObjectMapper(),
                new NativeToolSchemaMapper(registry), datasetService
        );
    }

    private static ToolRegistry registry(String... toolNames) {
        List<ToolDefinition> definitions = new ArrayList<>();
        for (String toolName : toolNames) {
            definitions.add(new ToolDefinition(toolName, "MCP-shaped tool.", List.of(
                    new ToolOperationDefinition("CALL", "Call.", List.of(
                            new ToolArgumentDefinition("session_id", "string", false, "Session id"),
                            new ToolArgumentDefinition("query", "string", false, "Search query")
                    ), false, ToolSafetyLevel.READ)
            )));
        }
        List<ToolDefinition> finalDefinitions = List.copyOf(definitions);
        return new ToolRegistry() {
            @Override
            public List<ToolDefinition> definitions() {
                return finalDefinitions;
            }

            @Override
            public String promptSection() {
                return "";
            }
        };
    }

    private static ModelResponse toolCallTurn(String callId, String functionName, Map<String, Object> arguments) {
        return new ModelResponse("", "", List.of(new ModelToolCall(callId, functionName, arguments)),
                "tool_calls", new ModelUsage(0, 0, 0));
    }

    private static ModelResponse textTurn(String content) {
        return new ModelResponse(content, "", List.of(), "stop", new ModelUsage(0, 0, 0));
    }

    /** Executes any registered tool with a canned result and records which real tools ran. */
    private static final class FakeToolManager implements ToolManager {

        private final Map<String, Map<String, Object>> resultsByTool;
        private final List<String> executed = new ArrayList<>();

        private FakeToolManager(Map<String, Map<String, Object>> resultsByTool) {
            this.resultsByTool = resultsByTool;
        }

        List<String> executedTools() {
            return executed;
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
            executed.add(request.toolName());
            Map<String, Object> data = resultsByTool.getOrDefault(request.toolName(), Map.of());
            return new ToolResult(true, request.toolName(), request.operation(), request.requestId(), request.conversationId(),
                    false, List.of(), "Tool completed.", data, "", "", false, "");
        }
    }

    /** Scripted {@link AIProvider} that records the exact outbound message list for every call. */
    private static final class CapturingProvider implements AIProvider {

        private final Deque<ModelResponse> turns;
        private final List<List<ModelMessage>> capturedMessages = new ArrayList<>();

        private CapturingProvider(Deque<ModelResponse> turns) {
            this.turns = turns;
        }

        int callCount() {
            return capturedMessages.size();
        }

        List<ModelMessage> messagesAt(int index) {
            return capturedMessages.get(index);
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
            capturedMessages.add(List.copyOf(messages));
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
