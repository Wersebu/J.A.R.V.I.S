package com.jarvis.tools.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.common.ai.AIJobType;
import com.jarvis.common.ai.AIProvider;
import com.jarvis.common.ai.AIProviderException;
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
 * Regression tests for bounded recovery from a malformed/truncated native tool call the provider
 * itself failed to parse - the exact reported production bug: Ollama returned HTTP 500 "error
 * parsing tool call: ... unexpected end of JSON input" for a {@code search_game_tree} call, and the
 * loop immediately gave up on tools entirely instead of giving the model a bounded chance to retry
 * the same call with valid JSON.
 */
class NativeToolLoopServiceProviderToolRepairTest {

    private static final String REAL_TOOL_MALFORMED_ERROR =
            "Ollama native tool chat failed with status 500: {\"error\":\"error parsing tool call: "
                    + "unexpected end of JSON input; raw='{\\\"path\\\":\\\"Workspace'\"}";
    private static final String XML_TAG_MISMATCH_ERROR =
            "Ollama native tool chat failed with status 500: {\"error\":\"XML syntax error on line 2: "
                    + "element <function> closed by </parameter>\"}";
    private static final String CONNECTION_FAILURE_ERROR = "Failed to communicate with Ollama native tool endpoint: Connection refused";

    @Test
    void firstMalformedToolCallIsRepairedAndTheRealToolStillExecutes() {
        Deque<Object> turns = new ArrayDeque<>();
        turns.add(REAL_TOOL_MALFORMED_ERROR);
        turns.add(toolCallTurn("mcp_roblox_search_game_tree__call", Map.of("query", "Workspace")));
        turns.add(textTurn("Workspace, ReplicatedStorage, ServerScriptService"));
        ScriptedFailureProvider provider = new ScriptedFailureProvider(turns);
        FakeToolManager toolManager = new FakeToolManager();
        NativeToolLoopService service = newService(provider, toolManager, robloxRegistry());

        ToolCallingResult result = service.execute(new ToolCallingRequest(
                "request-1", "conversation-1", "list the folders in my Roblox project",
                "Explore the Roblox Studio project tree.", "test", "Base prompt",
                new Brain(BrainType.FAST, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.LOW),
                KnowledgeMode.FAST
        ));

        assertThat(result.finalAnswer()).isEqualTo("Workspace, ReplicatedStorage, ServerScriptService");
        // 3 provider calls: malformed throw, corrected retry, final text - the real tool actually ran.
        assertThat(provider.callCount()).isEqualTo(3);
        assertThat(toolManager.executedTools()).containsExactly("mcp_roblox_search_game_tree");
        // Every repair-eligible call still carried the full original tool definitions - repair must
        // never disable tools on the first/second attempt (only handleProviderFailure's own final,
        // budget-exhausted fallback is allowed to call with an empty tool list).
        assertThat(provider.toolCountPerCall()).containsExactly(1, 1, 1);
    }

    @Test
    void xmlTagMismatchToolCallErrorIsAlsoRepaired() {
        // Real production case: Ollama templates native tool calls as XML-like tags internally for
        // some models, and rejects a malformed one with an XML parser error instead of a JSON one -
        // must be recognized as the same repairable failure class, not just a generic provider death.
        Deque<Object> turns = new ArrayDeque<>();
        turns.add(XML_TAG_MISMATCH_ERROR);
        turns.add(toolCallTurn("mcp_roblox_search_game_tree__call", Map.of("query", "Workspace")));
        turns.add(textTurn("Workspace, ReplicatedStorage, ServerScriptService"));
        ScriptedFailureProvider provider = new ScriptedFailureProvider(turns);
        FakeToolManager toolManager = new FakeToolManager();
        NativeToolLoopService service = newService(provider, toolManager, robloxRegistry());

        ToolCallingResult result = service.execute(new ToolCallingRequest(
                "request-1", "conversation-1", "list the folders in my Roblox project",
                "Explore the Roblox Studio project tree.", "test", "Base prompt",
                new Brain(BrainType.FAST, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.LOW),
                KnowledgeMode.FAST
        ));

        assertThat(result.finalAnswer()).isEqualTo("Workspace, ReplicatedStorage, ServerScriptService");
        assertThat(provider.callCount()).isEqualTo(3);
        assertThat(toolManager.executedTools()).containsExactly("mcp_roblox_search_game_tree");
    }

    @Test
    void repeatedMalformedToolCallsAreBoundedAndTerminateWithARealReason() {
        Deque<Object> turns = new ArrayDeque<>();
        turns.add(REAL_TOOL_MALFORMED_ERROR);
        turns.add(REAL_TOOL_MALFORMED_ERROR);
        turns.add(REAL_TOOL_MALFORMED_ERROR);
        turns.add(textTurn("")); // handleProviderFailure's own fallbackTextTurn, once repair is exhausted
        ScriptedFailureProvider provider = new ScriptedFailureProvider(turns);
        FakeToolManager toolManager = new FakeToolManager();
        NativeToolLoopService service = newService(provider, toolManager, robloxRegistry());

        ToolCallingResult result = service.execute(new ToolCallingRequest(
                "request-1", "conversation-1", "list the folders in my Roblox project",
                "Explore the Roblox Studio project tree.", "test", "Base prompt",
                new Brain(BrainType.FAST, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.LOW),
                KnowledgeMode.FAST
        ));

        // Terminates deterministically (proves no infinite loop) after exactly 2 bounded repair
        // attempts, then the existing final fallback path (unchanged) - 4 calls total, not endless.
        assertThat(provider.callCount()).isEqualTo(4);
        assertThat(result.handled()).isTrue();
        // The real failure reason is preserved, not replaced by a generic apology.
        assertThat(result.finalAnswer()).contains("error parsing tool call");
        assertThat(toolManager.executedTools()).isEmpty();
    }

    @Test
    void connectionFailureIsNeverTreatedAsAMalformedToolCallRepair() {
        Deque<Object> turns = new ArrayDeque<>();
        turns.add(CONNECTION_FAILURE_ERROR);
        turns.add(textTurn("")); // handleProviderFailure's own fallbackTextTurn
        ScriptedFailureProvider provider = new ScriptedFailureProvider(turns);
        FakeToolManager toolManager = new FakeToolManager();
        NativeToolLoopService service = newService(provider, toolManager, robloxRegistry());

        ToolCallingResult result = service.execute(new ToolCallingRequest(
                "request-1", "conversation-1", "list the folders in my Roblox project",
                "Explore the Roblox Studio project tree.", "test", "Base prompt",
                new Brain(BrainType.FAST, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.LOW),
                KnowledgeMode.FAST
        ));

        // No repair cycle: exactly 1 failed call + 1 fallbackTextTurn call, going straight to
        // handleProviderFailure - a connection failure retried with "fix your JSON" guidance could
        // never help and must not consume the repair budget.
        assertThat(provider.callCount()).isEqualTo(2);
        assertThat(provider.toolCountPerCall().get(1)).isEqualTo(0);
        assertThat(result.finalAnswer()).contains("Connection refused");
    }

    private NativeToolLoopService newService(ScriptedFailureProvider provider, ToolManager toolManager, ToolRegistry registry) {
        StoreAuditDatasetService datasetService = new StoreAuditDatasetService(new NoopCognitiveEventBus());
        return new NativeToolLoopService(
                List.of(provider), toolManager, query -> ToolIntent.SEARCH_WEB,
                new ToolRuntimeProperties(true, 15, 15, 2, 30, "native", 10, 20),
                new NoopCognitiveEventBus(), new ToolRuntimeDebugService(), new ObjectMapper(),
                new NativeToolSchemaMapper(registry), datasetService
        );
    }

    private static ToolRegistry robloxRegistry() {
        ToolDefinition searchTree = new ToolDefinition("mcp_roblox_search_game_tree", "MCP tool.", List.of(
                new ToolOperationDefinition("CALL", "Search.", List.of(
                        new ToolArgumentDefinition("query", "string", false, "Search query")), false, ToolSafetyLevel.READ)
        ));
        List<ToolDefinition> definitions = List.of(searchTree);
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

    /**
     * Executes any {@code mcp_roblox_*} call with a canned successful result and records which real
     * tools actually ran - proves a repaired call genuinely reaches tool execution.
     */
    private static final class FakeToolManager implements ToolManager {

        private final List<String> executed = new ArrayList<>();

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
            return new ToolResult(true, request.toolName(), request.operation(), request.requestId(), request.conversationId(),
                    false, List.of(), "MCP tool completed.", Map.of(), "", "", false, "");
        }
    }

    /**
     * Scripted {@link AIProvider} whose {@code toolChat} either throws an {@link
     * AIProviderException} (queue element is a {@code String} error message) or returns a scripted
     * {@link ModelResponse} - also records how many native tool definitions each call carried, so
     * tests can prove a repair retry never silently disabled tools.
     */
    private static final class ScriptedFailureProvider implements AIProvider {

        private final Deque<Object> turns;
        private final List<Integer> toolCountPerCall = new ArrayList<>();
        private int calls;

        private ScriptedFailureProvider(Deque<Object> turns) {
            this.turns = turns;
        }

        int callCount() {
            return calls;
        }

        List<Integer> toolCountPerCall() {
            return toolCountPerCall;
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
            toolCountPerCall.add(tools == null ? 0 : tools.size());
            Object next = turns.isEmpty() ? textTurn("") : turns.poll();
            if (next instanceof String errorMessage) {
                throw new AIProviderException(errorMessage);
            }
            return (ModelResponse) next;
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
