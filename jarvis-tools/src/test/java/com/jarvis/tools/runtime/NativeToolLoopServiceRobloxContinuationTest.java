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
 * Full end-to-end regression test for the reported production bug: J.A.R.V.I.S. asked to list a
 * Roblox project's folders called {@code list_roblox_studios} (a bootstrap/discovery MCP tool - it
 * only reports which Studio sessions are open), then proposed a {@code FINAL_ANSWER} describing
 * only the Studio session, even though its own text admitted that wasn't the actual folder list the
 * user asked for. {@link com.jarvis.tools.workflow.GenericGoalCompletionValidator} must reject that
 * proposed answer and force the loop to continue to {@code search_game_tree} (an answering-role MCP
 * tool), which is the one that can actually satisfy the user's request.
 *
 * <p>Uses the real {@code mcp_roblox_*__call} native tool-name shape ({@code
 * McpJarvisTool}/{@code McpToolDescriptor#jarvisToolName()}), not a simplified stand-in, so the
 * classifier is exercised exactly as it runs against genuine MCP tool calls in production.</p>
 */
class NativeToolLoopServiceRobloxContinuationTest {

    private static final String LIST_STUDIOS = "mcp_roblox_list_roblox_studios__call";
    private static final String SET_ACTIVE_STUDIO = "mcp_roblox_set_active_studio__call";
    private static final String SEARCH_GAME_TREE = "mcp_roblox_search_game_tree__call";

    @Test
    void bootstrapOnlyAnswerIsRejectedThenSearchGameTreeProducesTheRealFolderList() {
        Deque<ModelResponse> turns = new ArrayDeque<>();
        turns.add(toolCallTurn(LIST_STUDIOS, Map.of()));
        turns.add(textTurn("Znalazlem otwarte Studio: MyGame. To nie jest jednak lista folderow, "
                + "potrzebuje kolejnego narzedzia aby ja pobrac."));
        turns.add(toolCallTurn(SEARCH_GAME_TREE, Map.of("query", "*")));
        turns.add(textTurn("Workspace, ReplicatedStorage, ServerScriptService, Folder NPCs, Folder Maps"));
        ScriptedProvider provider = new ScriptedProvider(turns);
        NativeToolLoopService service = newService(provider, robloxRegistry());

        ToolCallingResult result = service.execute(new ToolCallingRequest(
                "request-1", "conversation-1", "list the folders in my Roblox project",
                "Explore the Roblox Studio project tree.", "test", "Base prompt",
                new Brain(BrainType.FAST, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.LOW),
                KnowledgeMode.FAST
        ));

        assertThat(result.handled()).isTrue();
        assertThat(result.finalAnswer())
                .isEqualTo("Workspace, ReplicatedStorage, ServerScriptService, Folder NPCs, Folder Maps");
        // All 4 scripted turns ran - the premature answer was genuinely rejected and re-entered,
        // not silently accepted.
        assertThat(provider.callCount()).isEqualTo(4);
    }

    @Test
    void multipleOpenStudiosSelectionStillCountsAsBootstrapOnly() {
        Deque<ModelResponse> turns = new ArrayDeque<>();
        turns.add(toolCallTurn(LIST_STUDIOS, Map.of()));
        turns.add(toolCallTurn(SET_ACTIVE_STUDIO, Map.of("studioId", "b")));
        turns.add(textTurn("Wybralem studio B, ale to nie jest lista folderow, potrzebuje kolejnego narzedzia."));
        turns.add(toolCallTurn(SEARCH_GAME_TREE, Map.of("query", "*")));
        turns.add(textTurn("Workspace, ReplicatedStorage, Folder Quests"));
        ScriptedProvider provider = new ScriptedProvider(turns);
        NativeToolLoopService service = newService(provider, robloxRegistry());

        ToolCallingResult result = service.execute(new ToolCallingRequest(
                "request-1", "conversation-1", "list the folders in my Roblox project",
                "Explore the Roblox Studio project tree.", "test", "Base prompt",
                new Brain(BrainType.FAST, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.LOW),
                KnowledgeMode.FAST
        ));

        assertThat(result.finalAnswer()).isEqualTo("Workspace, ReplicatedStorage, Folder Quests");
        assertThat(provider.callCount()).isEqualTo(5);
    }

    @Test
    void genuinelyEmptySearchResultIsAcceptedImmediatelyWithoutForcedRetry() {
        Deque<ModelResponse> turns = new ArrayDeque<>();
        turns.add(toolCallTurn(LIST_STUDIOS, Map.of()));
        turns.add(toolCallTurn(SEARCH_GAME_TREE, Map.of("query", "*")));
        turns.add(textTurn("Drzewo gry jest puste - projekt nie zawiera jeszcze zadnych folderow."));
        ScriptedProvider provider = new ScriptedProvider(turns);
        NativeToolLoopService service = newService(provider, robloxRegistry());

        ToolCallingResult result = service.execute(new ToolCallingRequest(
                "request-1", "conversation-1", "list the folders in my Roblox project",
                "Explore the Roblox Studio project tree.", "test", "Base prompt",
                new Brain(BrainType.FAST, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.LOW),
                KnowledgeMode.FAST
        ));

        assertThat(result.finalAnswer()).isEqualTo("Drzewo gry jest puste - projekt nie zawiera jeszcze zadnych folderow.");
        // No re-entry: a real (non-bootstrap) search_game_tree result already succeeded.
        assertThat(provider.callCount()).isEqualTo(3);
    }

    @Test
    void legitimateBootstrapOnlyAnswerIsAcceptedImmediately() {
        Deque<ModelResponse> turns = new ArrayDeque<>();
        turns.add(toolCallTurn(LIST_STUDIOS, Map.of()));
        turns.add(textTurn("Tak, jedno otwarte Studio jest polaczone: MyGame."));
        ScriptedProvider provider = new ScriptedProvider(turns);
        NativeToolLoopService service = newService(provider, robloxRegistry());

        ToolCallingResult result = service.execute(new ToolCallingRequest(
                "request-1", "conversation-1", "is a Roblox Studio session connected?",
                "Check for a connected Roblox Studio session.", "test", "Base prompt",
                new Brain(BrainType.FAST, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.LOW),
                KnowledgeMode.FAST
        ));

        assertThat(result.finalAnswer()).isEqualTo("Tak, jedno otwarte Studio jest polaczone: MyGame.");
        assertThat(provider.callCount()).isEqualTo(2);
    }

    @Test
    void persistentInsufficiencyAdmissionWithoutEverCallingAnAnsweringToolStillTerminates() {
        Deque<ModelResponse> turns = new ArrayDeque<>();
        turns.add(toolCallTurn(LIST_STUDIOS, Map.of()));
        String insufficient = "To nie jest lista folderow, potrzebuje kolejnego narzedzia.";
        turns.add(textTurn(insufficient));
        turns.add(textTurn(insufficient));
        turns.add(textTurn(insufficient));
        turns.add(textTurn(insufficient));
        ScriptedProvider provider = new ScriptedProvider(turns);
        NativeToolLoopService service = newService(provider, robloxRegistry());

        ToolCallingResult result = service.execute(new ToolCallingRequest(
                "request-1", "conversation-1", "list the folders in my Roblox project",
                "Explore the Roblox Studio project tree.", "test", "Base prompt",
                new Brain(BrainType.FAST, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.LOW),
                KnowledgeMode.FAST
        ));

        // The bounded completion-gate retry budget (shared with Store Audit, never doubled) still
        // guarantees termination even when the model keeps admitting insufficiency without ever
        // calling an answering-capable tool - it must not hang or loop forever.
        assertThat(result.handled()).isTrue();
        assertThat(result.finalAnswer()).isEqualTo(insufficient);
        assertThat(provider.callCount()).isEqualTo(5);
    }

    private NativeToolLoopService newService(ScriptedProvider provider, ToolRegistry registry) {
        StoreAuditDatasetService datasetService = new StoreAuditDatasetService(new NoopCognitiveEventBus());
        return new NativeToolLoopService(
                List.of(provider), new FakeRobloxToolManager(), query -> ToolIntent.SEARCH_WEB,
                new ToolRuntimeProperties(true, 15, 15, 2, 30, "native", 10, 20),
                new NoopCognitiveEventBus(), new ToolRuntimeDebugService(), new ObjectMapper(),
                new NativeToolSchemaMapper(registry), datasetService
        );
    }

    private static ToolRegistry robloxRegistry() {
        ToolDefinition listStudios = mcpTool("mcp_roblox_list_roblox_studios", List.of());
        ToolDefinition setActiveStudio = mcpTool("mcp_roblox_set_active_studio", List.of(
                new ToolArgumentDefinition("studioId", "string", true, "Studio id")));
        ToolDefinition searchGameTree = mcpTool("mcp_roblox_search_game_tree", List.of(
                new ToolArgumentDefinition("query", "string", false, "Search query")));
        List<ToolDefinition> definitions = List.of(listStudios, setActiveStudio, searchGameTree);
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

    private static ToolDefinition mcpTool(String name, List<ToolArgumentDefinition> arguments) {
        return new ToolDefinition(name, "MCP tool.", List.of(
                new ToolOperationDefinition("CALL", "Call MCP tool.", arguments, false, ToolSafetyLevel.READ)
        ));
    }

    private static ModelResponse toolCallTurn(String name, Map<String, Object> arguments) {
        return new ModelResponse("", "", List.of(new ModelToolCall("call-" + System.nanoTime(), name, arguments)),
                "tool_calls", new ModelUsage(0, 0, 0));
    }

    private static ModelResponse textTurn(String content) {
        return new ModelResponse(content, "", List.of(), "stop", new ModelUsage(0, 0, 0));
    }

    /**
     * Executes every {@code mcp_roblox_*} call with a canned successful result - real MCP transport
     * is irrelevant to this test, only the classifier's reaction to the resulting {@link
     * ToolRuntimeStep}s matters.
     */
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
