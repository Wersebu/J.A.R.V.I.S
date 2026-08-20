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
import com.jarvis.tools.schema.ToolJsonSchema;
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
    private static final String GET_STUDIO_STATE = "mcp_roblox_get_studio_state__call";
    private static final String SEARCH_GAME_TREE = "mcp_roblox_search_game_tree__call";
    private static final String SCRIPT_READ = "mcp_roblox_script_read__call";
    private static final String MULTI_EDIT = "mcp_roblox_multi_edit__call";
    private static final String GET_CONSOLE_OUTPUT = "mcp_roblox_get_console_output__call";
    private static final String INSPECT_CLIENT = "mcp_roblox_inspect_client_runtime__call";

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
    void wrongSearchWebIntentStillSendsTheCompleteRuntimeToolCatalogToNativeChat() {
        Deque<ModelResponse> turns = new ArrayDeque<>();
        turns.add(textTurn("Potrzebuje danych z Roblox MCP."));
        ScriptedProvider provider = new ScriptedProvider(turns);
        NativeToolLoopService service = newService(provider, robloxAndWebRegistry());

        service.execute(new ToolCallingRequest(
                "request-1", "conversation-1",
                "Podaj liste folderow dostepnych w aktualnie polaczonym projekcie Roblox Studio.",
                "List folders in the currently connected Roblox Studio project.",
                "Need live connected runtime inspection.",
                Map.of("provider", "roblox"),
                "Base prompt",
                new Brain(BrainType.FAST, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.LOW),
                KnowledgeMode.FAST,
                List.of()
        ));

        assertThat(provider.firstToolNames())
                .contains(LIST_STUDIOS, SEARCH_GAME_TREE, "web__search_web");
    }

    @Test
    void schemaRepairRejectsCamelCaseStudioIdThenContinuesWithStudioIdAndEditDatamodelType() {
        Deque<ModelResponse> turns = new ArrayDeque<>();
        turns.add(toolCallTurn(LIST_STUDIOS, Map.of()));
        turns.add(toolCallTurn(SEARCH_GAME_TREE, Map.of("studioId", "studio-1", "query", "Folder")));
        turns.add(toolCallTurn(GET_STUDIO_STATE, Map.of("studio_id", "studio-1")));
        turns.add(toolCallTurn(SEARCH_GAME_TREE, Map.of(
                "studio_id", "studio-1",
                "datamodel_type", "Edit",
                "query", "Folder")));
        turns.add(textTurn("Folder paths: Workspace/Maps, ReplicatedStorage/Shared/Folders"));
        ScriptedProvider provider = new ScriptedProvider(turns);
        RecordingRobloxToolManager manager = new RecordingRobloxToolManager(false);
        NativeToolLoopService service = newService(provider, strictRobloxRegistry(), manager);

        ToolCallingResult result = service.execute(new ToolCallingRequest(
                "request-1", "conversation-1",
                "Podaj liste folderow dostepnych w aktualnie polaczonym projekcie Roblox Studio.",
                "List folders in the currently connected Roblox Studio project.", "test", "Base prompt",
                new Brain(BrainType.FAST, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.LOW),
                KnowledgeMode.FAST
        ));

        assertThat(result.finalAnswer()).contains("Workspace/Maps", "ReplicatedStorage/Shared/Folders");
        assertThat(manager.executedToolNames()).containsExactly(
                "mcp_roblox_list_roblox_studios",
                "mcp_roblox_get_studio_state",
                "mcp_roblox_search_game_tree");
        assertThat(manager.executedRequests())
                .noneSatisfy(request -> assertThat(request.arguments()).containsKey("studioId"));
        assertThat(manager.executedRequests().get(2).arguments())
                .containsEntry("studio_id", "studio-1")
                .containsEntry("datamodel_type", "Edit");
        assertThat(provider.allMessageText())
                .contains("Unknown argument")
                .contains("studio_id")
                .contains("datamodel_type")
                .contains("Edit")
                .contains("Client")
                .contains("Server")
                .contains("studio-1");
    }

    @Test
    void failedSearchGameTreeAndPermissionQuestionCannotCompleteFolderGoal() {
        Deque<ModelResponse> turns = new ArrayDeque<>();
        turns.add(toolCallTurn(LIST_STUDIOS, Map.of()));
        turns.add(toolCallTurn(GET_STUDIO_STATE, Map.of("studio_id", "studio-1")));
        turns.add(toolCallTurn(SEARCH_GAME_TREE, Map.of(
                "studio_id", "studio-1",
                "datamodel_type", "Edit",
                "query", "Folder")));
        turns.add(textTurn("Search failed. Czy chcesz, zebym sprobowal ponownie?"));
        turns.add(textTurn("Search failed. Czy chcesz, zebym sprobowal ponownie?"));
        turns.add(textTurn("Search failed. Czy chcesz, zebym sprobowal ponownie?"));
        turns.add(textTurn("Search failed. Czy chcesz, zebym sprobowal ponownie?"));
        ScriptedProvider provider = new ScriptedProvider(turns);
        RecordingRobloxToolManager manager = new RecordingRobloxToolManager(true);
        NativeToolLoopService service = newService(provider, strictRobloxRegistry(), manager);

        ToolCallingResult result = service.execute(new ToolCallingRequest(
                "request-1", "conversation-1",
                "Podaj liste folderow dostepnych w aktualnie polaczonym projekcie Roblox Studio.",
                "List folders in the currently connected Roblox Studio project.", "test", "Base prompt",
                new Brain(BrainType.FAST, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.LOW),
                KnowledgeMode.FAST
        ));

        assertThat(result.finalAnswer()).doesNotContain("Czy chcesz");
        assertThat(result.finalAnswer()).contains("Nie mog");
        assertThat(provider.callCount()).isGreaterThan(4);
    }

    @Test
    void staleStudioIdRediscoveryRebindsAndRetriesOriginalReadWithoutClarification() {
        Deque<ModelResponse> turns = new ArrayDeque<>();
        turns.add(toolCallTurn(SCRIPT_READ, Map.of("studio_id", "studio-A", "path", "ServerScriptService/Fall.server.lua")));
        turns.add(textTurn("Root cause read."));
        ScriptedProvider provider = new ScriptedProvider(turns);
        RecoveringRobloxToolManager manager = new RecoveringRobloxToolManager();
        NativeToolLoopService service = newService(provider, recoveryRobloxRegistry(), manager);

        ToolCallingResult result = service.execute(new ToolCallingRequest(
                "request-1", "conversation-1",
                "sprawdz dlaczego gracz spada",
                "Read the Roblox script related to falling.", "test", "Base prompt",
                new Brain(BrainType.FAST, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.LOW),
                KnowledgeMode.FAST
        ));

        assertThat(result.finalAnswer()).isEqualTo("Root cause read.");
        assertThat(manager.executedToolNames()).containsExactly(
                "mcp_roblox_script_read",
                "mcp_roblox_list_roblox_studios",
                "mcp_roblox_script_read");
        assertThat(manager.executedRequests().get(2).arguments()).containsEntry("studio_id", "studio-B");
        assertThat(manager.executedRequests().stream()
                .filter(request -> request.toolName().equals("mcp_roblox_script_read"))
                .map(request -> request.arguments().get("studio_id"))
                .toList()).containsExactly("studio-A", "studio-B");
    }

    @Test
    void playModeEditRecoveryStopsPlayRetriesWriteAndReadsBack() {
        Deque<ModelResponse> turns = new ArrayDeque<>();
        turns.add(toolCallTurn(MULTI_EDIT, Map.of(
                "studio_id", "studio-1",
                "datamodel_type", "Edit",
                "edits", List.of(Map.of("path", "ServerScriptService/Fall.server.lua", "newText", "print('fixed')")))));
        turns.add(textTurn("Zmienilem skrypt i zweryfikowalem odczytem."));
        ScriptedProvider provider = new ScriptedProvider(turns);
        RecoveringRobloxToolManager manager = new RecoveringRobloxToolManager();
        manager.mode = "Play";
        NativeToolLoopService service = newService(provider, recoveryRobloxRegistry(), manager);

        ToolCallingResult result = service.execute(new ToolCallingRequest(
                "request-1", "conversation-1",
                "napraw ten skrypt Roblox",
                "Modify the Roblox script.", "test", "Base prompt",
                new Brain(BrainType.FAST, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.LOW),
                KnowledgeMode.FAST
        ));

        assertThat(result.finalAnswer()).contains("zweryfikowalem");
        assertThat(manager.executedToolNames()).containsExactly(
                "mcp_roblox_multi_edit",
                "mcp_roblox_get_studio_state",
                "mcp_roblox_start_stop_play",
                "mcp_roblox_get_studio_state",
                "mcp_roblox_multi_edit",
                "mcp_roblox_script_read");
        assertThat(manager.executedRequests().get(2).arguments()).containsEntry("is_start", false);
    }

    @Test
    void editModeClientToolRecoveryStartsPlayThenRetriesClientInspect() {
        Deque<ModelResponse> turns = new ArrayDeque<>();
        turns.add(toolCallTurn(INSPECT_CLIENT, Map.of("studio_id", "studio-1", "datamodel_type", "Client")));
        turns.add(textTurn("Client state inspected."));
        ScriptedProvider provider = new ScriptedProvider(turns);
        RecoveringRobloxToolManager manager = new RecoveringRobloxToolManager();
        manager.mode = "Edit";
        NativeToolLoopService service = newService(provider, recoveryRobloxRegistry(), manager);

        ToolCallingResult result = service.execute(new ToolCallingRequest(
                "request-1", "conversation-1",
                "sprawdz runtime clienta",
                "Inspect Client runtime.", "test", "Base prompt",
                new Brain(BrainType.FAST, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.LOW),
                KnowledgeMode.FAST
        ));

        assertThat(result.finalAnswer()).isEqualTo("Client state inspected.");
        assertThat(manager.executedToolNames()).containsExactly(
                "mcp_roblox_inspect_client_runtime",
                "mcp_roblox_get_studio_state",
                "mcp_roblox_start_stop_play",
                "mcp_roblox_get_studio_state",
                "mcp_roblox_inspect_client_runtime");
        assertThat(manager.executedRequests().get(2).arguments()).containsEntry("is_start", true);
    }

    @Test
    void notFoundScriptReadSearchesTreeAndRetriesResolvedPath() {
        Deque<ModelResponse> turns = new ArrayDeque<>();
        turns.add(toolCallTurn(SCRIPT_READ, Map.of("studio_id", "studio-1", "path", "ServerScriptService/Missing.lua")));
        turns.add(textTurn("Resolved and read moved script."));
        ScriptedProvider provider = new ScriptedProvider(turns);
        RecoveringRobloxToolManager manager = new RecoveringRobloxToolManager();
        NativeToolLoopService service = newService(provider, recoveryRobloxRegistry(), manager);

        service.execute(new ToolCallingRequest(
                "request-1", "conversation-1",
                "przeczytaj skrypt Missing.lua",
                "Read a Roblox script.", "test", "Base prompt",
                new Brain(BrainType.FAST, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.LOW),
                KnowledgeMode.FAST
        ));

        assertThat(manager.executedToolNames()).containsExactly(
                "mcp_roblox_script_read",
                "mcp_roblox_search_game_tree",
                "mcp_roblox_script_read");
        assertThat(manager.executedRequests().get(2).arguments())
                .containsEntry("path", "ServerScriptService/Actual/Missing.lua");
    }

    @Test
    void consoleClueAloneCannotCompleteRootCauseDiagnosisUntilScriptIsRead() {
        Deque<ModelResponse> turns = new ArrayDeque<>();
        turns.add(toolCallTurn(GET_CONSOLE_OUTPUT, Map.of("studio_id", "studio-1")));
        turns.add(textTurn("Przyczyna to FooBar w skrypcie."));
        turns.add(toolCallTurn(SCRIPT_READ, Map.of("studio_id", "studio-1", "path", "ServerScriptService/Fall.server.lua")));
        turns.add(textTurn("Zweryfikowana przyczyna: skrypt odwoluje sie do FooBar."));
        ScriptedProvider provider = new ScriptedProvider(turns);
        RecoveringRobloxToolManager manager = new RecoveringRobloxToolManager();
        NativeToolLoopService service = newService(provider, recoveryRobloxRegistry(), manager);

        ToolCallingResult result = service.execute(new ToolCallingRequest(
                "request-1", "conversation-1",
                "sprawdz dlaczego gracz spada",
                "Diagnose why the Roblox player falls.", "test", "Base prompt",
                new Brain(BrainType.FAST, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.LOW),
                KnowledgeMode.FAST
        ));

        assertThat(result.finalAnswer()).contains("Zweryfikowana przyczyna");
        assertThat(manager.executedToolNames()).containsExactly(
                "mcp_roblox_get_console_output",
                "mcp_roblox_script_read");
        assertThat(provider.callCount()).isEqualTo(4);
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
        return newService(provider, registry, new RecordingRobloxToolManager(false));
    }

    private NativeToolLoopService newService(ScriptedProvider provider, ToolRegistry registry, ToolManager toolManager) {
        StoreAuditDatasetService datasetService = new StoreAuditDatasetService(new NoopCognitiveEventBus());
        return new NativeToolLoopService(
                List.of(provider), toolManager, query -> ToolIntent.SEARCH_WEB,
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

    private static ToolRegistry strictRobloxRegistry() {
        ToolDefinition listStudios = mcpTool("mcp_roblox_list_roblox_studios", List.of());
        ToolDefinition getStudioState = mcpTool("mcp_roblox_get_studio_state", List.of(
                new ToolArgumentDefinition("studio_id", true, ToolJsonSchema.string("Studio id from list_roblox_studios."))
        ));
        ToolDefinition searchGameTree = mcpTool("mcp_roblox_search_game_tree", List.of(
                new ToolArgumentDefinition("studio_id", true,
                        ToolJsonSchema.string("Studio id from list_roblox_studios.")),
                new ToolArgumentDefinition("datamodel_type", true,
                        ToolJsonSchema.string("Datamodel type from get_studio_state.")
                                .withEnumValues(List.of("Edit", "Client", "Server"))),
                new ToolArgumentDefinition("query", false,
                        ToolJsonSchema.string("Search query."))
        ));
        List<ToolDefinition> definitions = List.of(listStudios, getStudioState, searchGameTree);
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

    private static ToolRegistry recoveryRobloxRegistry() {
        ToolDefinition listStudios = mcpTool("mcp_roblox_list_roblox_studios", List.of());
        ToolDefinition getStudioState = mcpTool("mcp_roblox_get_studio_state", List.of(
                new ToolArgumentDefinition("studio_id", true, ToolJsonSchema.string("Studio id."))
        ));
        ToolDefinition startStopPlay = mcpTool("mcp_roblox_start_stop_play", List.of(
                new ToolArgumentDefinition("studio_id", true, ToolJsonSchema.string("Studio id.")),
                new ToolArgumentDefinition("is_start", true, ToolJsonSchema.bool("True to start Play, false to stop."))
        ));
        ToolDefinition searchGameTree = mcpTool("mcp_roblox_search_game_tree", List.of(
                new ToolArgumentDefinition("studio_id", false, ToolJsonSchema.string("Studio id.")),
                new ToolArgumentDefinition("datamodel_type", false, ToolJsonSchema.string("Datamodel type.")),
                new ToolArgumentDefinition("query", false, ToolJsonSchema.string("Search query."))
        ));
        ToolDefinition scriptRead = mcpTool("mcp_roblox_script_read", List.of(
                new ToolArgumentDefinition("studio_id", true, ToolJsonSchema.string("Studio id.")),
                new ToolArgumentDefinition("datamodel_type", false, ToolJsonSchema.string("Datamodel type.")),
                new ToolArgumentDefinition("path", true, ToolJsonSchema.string("Script path."))
        ));
        ToolDefinition multiEdit = mcpTool("mcp_roblox_multi_edit", List.of(
                new ToolArgumentDefinition("studio_id", true, ToolJsonSchema.string("Studio id.")),
                new ToolArgumentDefinition("datamodel_type", true, ToolJsonSchema.string("Datamodel type.")),
                new ToolArgumentDefinition("edits", true, ToolJsonSchema.arrayOf(ToolJsonSchema.object(Map.of(
                        "path", ToolJsonSchema.string("Script path."),
                        "newText", ToolJsonSchema.string("New source.")
                ), List.of("path"), "One edit."), "Edits."))
        ));
        ToolDefinition console = mcpTool("mcp_roblox_get_console_output", List.of(
                new ToolArgumentDefinition("studio_id", true, ToolJsonSchema.string("Studio id."))
        ));
        ToolDefinition inspectClient = mcpTool("mcp_roblox_inspect_client_runtime", List.of(
                new ToolArgumentDefinition("studio_id", true, ToolJsonSchema.string("Studio id.")),
                new ToolArgumentDefinition("datamodel_type", true, ToolJsonSchema.string("Datamodel type."))
        ));
        List<ToolDefinition> definitions = List.of(listStudios, getStudioState, startStopPlay, searchGameTree,
                scriptRead, multiEdit, console, inspectClient);
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

    private static ToolRegistry robloxAndWebRegistry() {
        List<ToolDefinition> definitions = new ArrayList<>(robloxRegistry().definitions());
        definitions.add(new ToolDefinition("web", "Public web search", List.of(
                new ToolOperationDefinition("SEARCH_WEB", "Search public web", List.of(
                        new ToolArgumentDefinition("query", "string", true, "Search query")
                ), false, ToolSafetyLevel.READ)
        )));
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
    private static final class RecordingRobloxToolManager implements ToolManager {

        private final boolean failSearchGameTree;
        private final List<ToolRequest> executedRequests = new ArrayList<>();

        private RecordingRobloxToolManager(boolean failSearchGameTree) {
            this.failSearchGameTree = failSearchGameTree;
        }

        List<ToolRequest> executedRequests() {
            return executedRequests;
        }

        List<String> executedToolNames() {
            return executedRequests.stream().map(ToolRequest::toolName).toList();
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
            executedRequests.add(request);
            if ("mcp_roblox_list_roblox_studios".equals(request.toolName())) {
                return new ToolResult(true, request.toolName(), request.operation(), request.requestId(), request.conversationId(),
                        false, List.of("studio-1"), "Connected Roblox Studio instances.",
                        Map.of("studios", List.of(Map.of("studio_id", "studio-1", "name", "MyGame"))),
                        "", "", false, "");
            }
            if ("mcp_roblox_get_studio_state".equals(request.toolName())) {
                return new ToolResult(true, request.toolName(), request.operation(), request.requestId(), request.conversationId(),
                        false, List.of("studio-1"), "Studio state.",
                        Map.of("studio_id", "studio-1", "availableDatamodelTypes", List.of("Edit", "Client", "Server"), "mode", "Edit"),
                        "", "", false, "");
            }
            if ("mcp_roblox_search_game_tree".equals(request.toolName()) && failSearchGameTree) {
                return new ToolResult(false, request.toolName(), request.operation(), request.requestId(), request.conversationId(),
                        false, List.of("studio-1"), "search_game_tree failed.",
                        Map.of(), "MCP_ERROR", "search_game_tree failed.", false, "");
            }
            if ("mcp_roblox_search_game_tree".equals(request.toolName())) {
                return new ToolResult(true, request.toolName(), request.operation(), request.requestId(), request.conversationId(),
                        false, List.of("studio-1"), "Found folders.",
                        Map.of("folders", List.of("Workspace/Maps", "ReplicatedStorage/Shared/Folders")),
                        "", "", false, "");
            }
            return new ToolResult(true, request.toolName(), request.operation(), request.requestId(), request.conversationId(),
                    false, List.of(), "MCP tool completed.", Map.of(), "", "", false, "");
        }
    }

    private static final class RecoveringRobloxToolManager implements ToolManager {

        private final List<ToolRequest> executedRequests = new ArrayList<>();
        private String mode = "Edit";
        private boolean sourceChanged;

        List<ToolRequest> executedRequests() {
            return executedRequests;
        }

        List<String> executedToolNames() {
            return executedRequests.stream().map(ToolRequest::toolName).toList();
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
            executedRequests.add(request);
            String tool = request.toolName();
            if ("mcp_roblox_list_roblox_studios".equals(tool)) {
                return success(request, "Connected Roblox Studio instances.",
                        Map.of("studios", List.of(Map.of("studio_id", "studio-B", "name", "MyGame", "placeId", "place-1"))));
            }
            if ("mcp_roblox_get_studio_state".equals(tool)) {
                return success(request, "Studio state.", Map.of("studio_id", studioId(request), "mode", mode));
            }
            if ("mcp_roblox_start_stop_play".equals(tool)) {
                boolean start = Boolean.TRUE.equals(request.arguments().get("is_start"));
                mode = start ? "Client" : "Edit";
                return success(request, "Mode changed.", Map.of("studio_id", studioId(request), "mode", mode));
            }
            if ("mcp_roblox_script_read".equals(tool)) {
                String studioId = studioId(request);
                String path = String.valueOf(request.arguments().getOrDefault("path", ""));
                if ("studio-A".equals(studioId)) {
                    return failure(request, "SESSION_NOT_CONNECTED", "The requested studio_id is not connected");
                }
                if (path.endsWith("Missing.lua") && !path.contains("Actual")) {
                    return failure(request, "TARGET_NOT_FOUND", "Target not found");
                }
                return success(request, "Script source.", Map.of("studio_id", studioId, "path", path,
                        "source", sourceChanged ? "print('fixed')" : "workspace.Terrain.FooBar"));
            }
            if ("mcp_roblox_search_game_tree".equals(tool)) {
                return success(request, "Found script.", Map.of("matches",
                        List.of(Map.of("path", "ServerScriptService/Actual/Missing.lua", "className", "Script"))));
            }
            if ("mcp_roblox_multi_edit".equals(tool)) {
                if (!"Edit".equals(mode)) {
                    return failure(request, "WRONG_RUNTIME_MODE", "multi_edit requires Edit datamodel");
                }
                sourceChanged = true;
                return success(request, "Write request accepted.", Map.of("changed", true));
            }
            if ("mcp_roblox_get_console_output".equals(tool)) {
                return success(request, "Runtime error: FooBar is not a valid member of Workspace",
                        Map.of("entries", List.of("Runtime error: FooBar is not a valid member of Workspace")));
            }
            if ("mcp_roblox_inspect_client_runtime".equals(tool)) {
                if ("Edit".equals(mode)) {
                    return failure(request, "WRONG_RUNTIME_MODE", "Client unavailable; requires Client datamodel");
                }
                return success(request, "Client runtime state.", Map.of("mode", mode));
            }
            return success(request, "MCP tool completed.", Map.of());
        }

        private String studioId(ToolRequest request) {
            return String.valueOf(request.arguments().getOrDefault("studio_id", ""));
        }

        private ToolResult success(ToolRequest request, String message, Map<String, Object> data) {
            return new ToolResult(true, request.toolName(), request.operation(), request.requestId(), request.conversationId(),
                    false, List.of("studio-1"), message, data, "", "", false, "");
        }

        private ToolResult failure(ToolRequest request, String code, String message) {
            return new ToolResult(false, request.toolName(), request.operation(), request.requestId(), request.conversationId(),
                    false, List.of(), message, Map.of(), code, message, false, "");
        }
    }

    private static final class ScriptedProvider implements AIProvider {

        private final Deque<ModelResponse> turns;
        private int calls;
        private List<String> firstToolNames = List.of();
        private final List<List<ModelMessage>> messageSnapshots = new ArrayList<>();

        private ScriptedProvider(Deque<ModelResponse> turns) {
            this.turns = turns;
        }

        int callCount() {
            return calls;
        }

        List<String> firstToolNames() {
            return firstToolNames;
        }

        String allMessageText() {
            StringBuilder builder = new StringBuilder();
            for (List<ModelMessage> messages : messageSnapshots) {
                for (ModelMessage message : messages) {
                    builder.append(message.content()).append('\n');
                }
            }
            return builder.toString();
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
            if (calls == 0) {
                firstToolNames = tools.stream().map(NativeToolDefinition::name).toList();
            }
            messageSnapshots.add(List.copyOf(messages));
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
