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
import com.jarvis.tools.dataset.CandidateRecord;
import com.jarvis.tools.dataset.StoreAuditDataset;
import com.jarvis.tools.dataset.StoreAuditDatasetService;
import com.jarvis.tools.dataset.StoreDatasetTool;
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
 * Regression tests for the GPT-OSS 20B / native tool loop Store Audit integration bug: a real
 * {@code storeDataset} scheduling request whose main model already returned {@code TOOL_REQUEST}
 * was misclassified as {@code NO_TOOL} by {@code NativeToolLoopService.resolveIntent()} (a
 * word-boundary regex bug that never matched "geocode"/"geocoding"/"routes" - only the bare stems),
 * combined with an unbounded "live evidence required" recovery branch driven by a separate
 * {@code InformationFreshnessEvaluator} false positive (the substring "now" matching inside the
 * Polish place name "Nowej"). The combination produced 30 consecutive {@code toolCalls=0}
 * {@code FINAL_CONTENT} turns and a {@code MAX_TURNS_REACHED} failure with zero tool calls ever
 * executed, sending the model all 61 registered tools (including 27 unrelated Roblox tools)
 * regardless.
 */
class NativeToolLoopServiceStoreAuditIntentRegressionTest {

    private static final String WORKFLOW_DOCUMENT_PATH = "Work/Scheduling/StoreAuditScheduleWorkflow.md";

    // TEST 1: the exact reported production goal text ("geocode addresses, plan routes...") must
    // never resolve to NO_TOOL, even when the message-level intent detector is a dumb stub. Fixes
    // the word-boundary regex bug (\b(...)\b only ever matched "geocod"/"route" literally, never
    // "geocode"/"geocoding"/"routes").
    @Test
    void geocodeAddressesAndPlanRoutesGoalIsNeverClassifiedAsNoTool() {
        RecordingCognitiveEventBus events = new RecordingCognitiveEventBus();
        StoreAuditDatasetService datasetService = new StoreAuditDatasetService(events);
        ScriptedProvider provider = new ScriptedProvider(new ArrayDeque<>(List.of(
                textTurn("Zestaw danych zostal juz przygotowany wczesniej.")
        )));
        NativeToolLoopService service = new NativeToolLoopService(
                List.of(provider), new NoopToolManager(), query -> ToolIntent.NO_TOOL,
                new ToolRuntimeProperties(true, 10, 10, 2, 30, "native", 10, 20),
                events, new ToolRuntimeDebugService(), new ObjectMapper(),
                new NativeToolSchemaMapper(minimalStoreAuditRegistry()), datasetService
        );

        service.execute(new ToolCallingRequest(
                "request-1", "conversation-1", "wolalbym robic audyty rownomiernie",
                "Create a canonical storeDataset with the provided list of Stokrotka and Biedronka "
                        + "stores for September 2026, then verify, set even distribution on Tuesdays and "
                        + "Wednesdays with start point 05-500, geocode addresses, plan routes, submit "
                        + "schedule and return final table",
                "Need to create, verify, set preferences, geocode, plan, and submit the audit schedule",
                "Base prompt",
                new Brain(BrainType.FAST, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.LOW),
                KnowledgeMode.FAST
        ));

        String resolvedIntent = events.lastStartedResolvedIntent();
        assertThat(resolvedIntent).isNotEqualTo("NO_TOOL");
        assertThat(resolvedIntent).isIn("LOCATION", "STORE_AUDIT");
    }

    // TEST 1b: the underlying regex fix in isolation - just "geocode addresses" and "plan routes"
    // (no storeDataset/audit vocabulary at all) must resolve to LOCATION, not NO_TOOL.
    @Test
    void geocodeAndRouteStemsAloneResolveToLocationNotNoTool() {
        RecordingCognitiveEventBus events = new RecordingCognitiveEventBus();
        StoreAuditDatasetService datasetService = new StoreAuditDatasetService(events);
        ScriptedProvider provider = new ScriptedProvider(new ArrayDeque<>(List.of(textTurn("Gotowe."))));
        NativeToolLoopService service = new NativeToolLoopService(
                List.of(provider), new NoopToolManager(), query -> ToolIntent.NO_TOOL,
                new ToolRuntimeProperties(true, 10, 10, 2, 30, "native", 10, 20),
                events, new ToolRuntimeDebugService(), new ObjectMapper(),
                new NativeToolSchemaMapper(minimalStoreAuditRegistry()), datasetService
        );

        service.execute(new ToolCallingRequest(
                "request-1", "conversation-1", "kontynuuj",
                "geocode addresses, then plan routes for the trip", "final step", "Base prompt",
                new Brain(BrainType.FAST, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.LOW),
                KnowledgeMode.FAST
        ));

        assertThat(events.lastStartedResolvedIntent()).isEqualTo("LOCATION");
    }

    // TEST 2: a Polish goal about an audit schedule ("grafik audytow") must also select a real
    // workflow, not NO_TOOL - dedicated Store Audit workflow recognition, not just the location regex.
    @Test
    void polishAuditScheduleGoalSelectsStoreAuditWorkflow() {
        RecordingCognitiveEventBus events = new RecordingCognitiveEventBus();
        StoreAuditDatasetService datasetService = new StoreAuditDatasetService(events);
        ScriptedProvider provider = new ScriptedProvider(new ArrayDeque<>(List.of(textTurn("Gotowe."))));
        NativeToolLoopService service = new NativeToolLoopService(
                List.of(provider), new NoopToolManager(), query -> ToolIntent.NO_TOOL,
                new ToolRuntimeProperties(true, 10, 10, 2, 30, "native", 10, 20),
                events, new ToolRuntimeDebugService(), new ObjectMapper(),
                new NativeToolSchemaMapper(minimalStoreAuditRegistry()), datasetService
        );

        service.execute(new ToolCallingRequest(
                "request-1", "conversation-1", "przygotuj to na wrzesien",
                "Przygotuj grafik audytow na wrzesien dla podanych sklepow.", "test", "Base prompt",
                new Brain(BrainType.FAST, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.LOW),
                KnowledgeMode.FAST
        ));

        assertThat(events.lastStartedResolvedIntent()).isEqualTo("STORE_AUDIT");
    }

    // TEST 3: once a real Store Audit dataset already exists for this conversation (a genuine,
    // stateful signal from an earlier turn), a completely generic later-turn message/goal must still
    // resolve to STORE_AUDIT, never NO_TOOL - the main model's already-confirmed TOOL_REQUEST decision
    // (this method only ever runs after that) must never be silently degraded back to "no tool needed".
    @Test
    void existingDatasetStatePreventsDegradingToNoToolOnAGenericLaterTurn() {
        RecordingCognitiveEventBus events = new RecordingCognitiveEventBus();
        StoreAuditDatasetService datasetService = new StoreAuditDatasetService(events);
        datasetService.registerAttachments("earlier-request", "conversation-1", List.of("att-1"));
        datasetService.createDataset("earlier-request", 1, 0, List.of("att-1"), List.of(
                new CandidateRecord("Biedronka", "Miasto", "Ulica", "1", "00-001", "Ulica 1, 00-001 Miasto", "att-1", 1)
        ));
        ScriptedProvider provider = new ScriptedProvider(new ArrayDeque<>(List.of(textTurn("Gotowe."))));
        NativeToolLoopService service = new NativeToolLoopService(
                List.of(provider), new NoopToolManager(), query -> ToolIntent.NO_TOOL,
                new ToolRuntimeProperties(true, 10, 10, 2, 30, "native", 10, 20),
                events, new ToolRuntimeDebugService(), new ObjectMapper(),
                new NativeToolSchemaMapper(minimalStoreAuditRegistry()), datasetService
        );

        service.execute(new ToolCallingRequest(
                "request-2", "conversation-1", "wolalbym robic to rownomiernie we wtorki i srody",
                "Set even distribution on Tuesdays and Wednesdays.", "preferences", "Base prompt",
                new Brain(BrainType.FAST, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.LOW),
                KnowledgeMode.FAST
        ));

        assertThat(events.lastStartedResolvedIntent()).isEqualTo("STORE_AUDIT");
    }

    // TEST 4: a confidently recognized Store Audit workflow gets only Store-Audit-relevant tool
    // definitions - never the unrelated Roblox/marketplace/web tools also registered in the runtime
    // catalog. The exact reported bug: totalAvailableTools=61 including 27 Roblox tools for a request
    // that had nothing to do with Roblox.
    @Test
    void storeAuditWorkflowReceivesOnlyRelatedToolDefinitionsNeverRoblox() {
        RecordingCognitiveEventBus events = new RecordingCognitiveEventBus();
        StoreAuditDatasetService datasetService = new StoreAuditDatasetService(events);
        ScriptedProvider provider = new ScriptedProvider(new ArrayDeque<>(List.of(textTurn("Gotowe."))));
        NativeToolLoopService service = new NativeToolLoopService(
                List.of(provider), new NoopToolManager(), query -> ToolIntent.NO_TOOL,
                new ToolRuntimeProperties(true, 10, 10, 2, 30, "native", 10, 20),
                events, new ToolRuntimeDebugService(), new ObjectMapper(),
                new NativeToolSchemaMapper(fullMixedRegistryWithRobloxAndWeb()), datasetService
        );

        service.execute(new ToolCallingRequest(
                "request-1", "conversation-1", "kontynuuj",
                "Create a canonical storeDataset, geocode addresses, plan routes, submit schedule.",
                "test", "Base prompt",
                new Brain(BrainType.FAST, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.LOW),
                KnowledgeMode.FAST
        ));

        assertThat(provider.lastToolNames()).isNotEmpty();
        assertThat(provider.lastToolNames()).allSatisfy(name -> assertThat(name)
                .satisfiesAnyOf(
                        n -> assertThat(n).startsWith("storedataset__"),
                        n -> assertThat(n).startsWith("knowledge__"),
                        n -> assertThat(n).startsWith("location__"),
                        n -> assertThat(n).startsWith("system__")
                ));
        assertThat(provider.lastToolNames()).noneMatch(name -> name.startsWith("mcp_roblox"));
        assertThat(provider.lastToolNames()).noneMatch(name -> name.startsWith("web__"));
    }

    // TEST 5: two consecutive FINAL_CONTENT turns with toolCalls=0 must terminate the loop with
    // NO_NATIVE_TOOL_CALL_PROGRESS instead of bouncing all the way to MAX_TURNS_REACHED (30 turns in
    // the reported bug). A generous maxCalls (30) proves this is the no-progress guard firing, not the
    // turn budget. The dataset is genuinely touched (CREATE_DATASET, stage=EXTRACTED) so the
    // workflow-completion gate correctly refuses to accept "Pracuje nad tym." as done - without that,
    // a tool-less first answer is legitimately accepted immediately (no bug there), so the model's
    // repeated non-answers would never even be evaluated.
    @Test
    void twoConsecutiveNoToolCallFinalContentTurnsStopTheLoopInsteadOfBurningTheFullBudget() {
        StoreAuditDatasetService datasetService = new StoreAuditDatasetService(new NoopCognitiveEventBus());
        datasetService.registerAttachments("request-1", "conversation-1", List.of());
        StoreDatasetTool storeDatasetTool = new StoreDatasetTool(datasetService);

        Deque<ModelResponse> turns = new ArrayDeque<>();
        turns.add(toolCallTurn("storedataset__create_dataset", Map.of(
                "sourceImageCount", 1, "expectedRecordCount", 2, "records", List.of(
                        Map.of("network", "Biedronka", "fullAddress", "Adres 1", "sourceRow", 1),
                        Map.of("network", "Biedronka", "fullAddress", "Adres 2", "sourceRow", 2)))));
        turns.add(textTurn("Pracuje nad tym."));
        turns.add(textTurn("Nadal pracuje nad tym."));
        turns.add(textTurn("To sie nigdy nie powinno wykonac."));
        ScriptedProvider provider = new ScriptedProvider(turns);
        FullToolManager toolManager = new FullToolManager(datasetService, storeDatasetTool);
        RecordingCognitiveEventBus events = new RecordingCognitiveEventBus();

        NativeToolLoopService service = new NativeToolLoopService(
                List.of(provider), toolManager, query -> ToolIntent.NO_TOOL,
                new ToolRuntimeProperties(true, 30, 30, 2, 30, "native", 10, 20),
                events, new ToolRuntimeDebugService(), new ObjectMapper(),
                new NativeToolSchemaMapper(minimalStoreAuditRegistry()), datasetService
        );

        ToolCallingResult result = service.execute(new ToolCallingRequest(
                "request-1", "conversation-1", "przygotuj grafik", "Create the Store Audit dataset.",
                "test", "Base prompt",
                new Brain(BrainType.FAST, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.LOW),
                KnowledgeMode.FAST
        ));

        assertThat(result.handled()).isTrue();
        // create_dataset(1) + 2 consecutive rejected FINAL_CONTENT turns(2,3) - stopped there, never
        // reaching the 3rd scripted text turn that "should never execute".
        assertThat(provider.callCount()).isEqualTo(3);
        assertThat(result.terminationInfo().terminationReason()).isEqualTo(ToolLoopTerminationReason.NO_NATIVE_TOOL_CALL_PROGRESS);
        assertThat(result.terminationInfo().completed()).isFalse();
    }

    // TEST 6: a genuine native tool call in between resets the no-progress counter - the guard only
    // ever fires on CONSECUTIVE no-tool-call turns, never a cumulative count across the whole loop.
    @Test
    void aRealToolCallResetsTheNoProgressCounter() {
        StoreAuditDatasetService datasetService = new StoreAuditDatasetService(new NoopCognitiveEventBus());
        datasetService.registerAttachments("request-1", "conversation-1", List.of());
        StoreDatasetTool storeDatasetTool = new StoreDatasetTool(datasetService);

        Deque<ModelResponse> turns = new ArrayDeque<>();
        turns.add(toolCallTurn("storedataset__create_dataset", Map.of(
                "sourceImageCount", 1, "expectedRecordCount", 2, "records", List.of(
                        Map.of("network", "Biedronka", "fullAddress", "Adres 1", "sourceRow", 1),
                        Map.of("network", "Biedronka", "fullAddress", "Adres 2", "sourceRow", 2)))));
        // One rejected FINAL_CONTENT turn (counter=1)...
        turns.add(textTurn("Pracuje nad tym."));
        // ...then a REAL tool call resets the counter back to 0, so the next single rejected
        // FINAL_CONTENT turn alone must not trip the 2-consecutive guard.
        turns.add(toolCallTurn("storedataset__verify_dataset", Map.of("verifications", List.of(
                Map.of("recordIndex", 1, "status", "VERIFIED"),
                Map.of("recordIndex", 2, "status", "VERIFIED")))));
        turns.add(textTurn("Nadal pracuje nad tym."));
        ScriptedProvider provider = new ScriptedProvider(turns);
        FullToolManager toolManager = new FullToolManager(datasetService, storeDatasetTool);
        RecordingCognitiveEventBus events = new RecordingCognitiveEventBus();

        NativeToolLoopService service = new NativeToolLoopService(
                List.of(provider), toolManager, query -> ToolIntent.NO_TOOL,
                new ToolRuntimeProperties(true, 30, 30, 2, 30, "native", 10, 20),
                events, new ToolRuntimeDebugService(), new ObjectMapper(),
                new NativeToolSchemaMapper(minimalStoreAuditRegistry()), datasetService
        );

        ToolCallingResult result = service.execute(new ToolCallingRequest(
                "request-1", "conversation-1", "przygotuj grafik", "Create the Store Audit dataset.",
                "test", "Base prompt",
                new Brain(BrainType.FAST, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.LOW),
                KnowledgeMode.FAST
        ));

        assertThat(result.handled()).isTrue();
        // All 4 scripted turns ran (and the loop then fell back to the completion gate's own bounded
        // retry, not my guard) - proof the VERIFY_DATASET tool call in the middle reset the counter,
        // since 2 non-consecutive single no-tool-call turns must never trip a "2 CONSECUTIVE" guard.
        assertThat(provider.callCount()).isGreaterThanOrEqualTo(4);
        assertThat(result.terminationInfo().terminationReason()).isNotEqualTo(ToolLoopTerminationReason.NO_NATIVE_TOOL_CALL_PROGRESS);
    }

    // TEST 7: the live-evidence recovery branch (freshness=MUST_BE_LIVE with no live evidence
    // collected) has its own small bounded retry budget, independent of the general no-progress
    // backstop - proven here with a generous general threshold (10) so only the dedicated live-evidence
    // budget (default 3) can be what stops the loop.
    @Test
    void liveEvidenceRecoveryHasItsOwnBoundedRetryBudget() {
        Deque<ModelResponse> turns = new ArrayDeque<>();
        for (int i = 0; i < 6; i++) {
            turns.add(textTurn("Sprawdzam aktualna cene."));
        }
        ScriptedProvider provider = new ScriptedProvider(turns);
        RecordingCognitiveEventBus events = new RecordingCognitiveEventBus();

        NativeToolLoopService service = new NativeToolLoopService(
                List.of(provider), new NoopToolManager(), query -> ToolIntent.NO_TOOL,
                // maxConsecutiveNoToolProgressTurns=10 (general backstop deliberately loose here),
                // maxLiveEvidenceRecoveryAttempts left at its own default (3).
                new ToolRuntimeProperties(true, 30, 30, 2, 30, "native", 10, 20, 10, 3),
                events, new ToolRuntimeDebugService(), new ObjectMapper(),
                new NativeToolSchemaMapper(minimalStoreAuditRegistry()),
                new StoreAuditDatasetService(events)
        );

        ToolCallingResult result = service.execute(new ToolCallingRequest(
                "request-1", "conversation-1", "jaka jest aktualna cena tego produktu",
                "Report the current price.", "live price lookup", "Base prompt",
                new Brain(BrainType.FAST, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.LOW),
                KnowledgeMode.FAST
        ));

        assertThat(result.handled()).isTrue();
        // 3 bounded LIVE_DATA_REQUIRED retries, then the 4th turn falls through the exhausted gate
        // instead of looping a 5th/6th time - proven by stopping well short of the 6 scripted turns
        // and short of the general backstop (10) that would otherwise mask this specific bound.
        assertThat(provider.callCount()).isEqualTo(4);
    }

    // A production-shaped end-to-end regression: GPT-OSS-style behavior of writing a TOOL_REQUEST
    // plan as plain text on the first turn instead of a native tool call must still recover into a
    // real storeDataset workflow rather than exhausting the loop with zero tool calls (the exact
    // reported bug: 30 turns, toolCalls=0 throughout).
    @Test
    void modelWritingATextPlanFirstStillReachesRealToolCallsInsteadOfExhaustingTheLoop() {
        StoreAuditDatasetService datasetService = new StoreAuditDatasetService(new NoopCognitiveEventBus());
        datasetService.registerAttachments("request-1", "conversation-1", List.of());
        StoreDatasetTool storeDatasetTool = new StoreDatasetTool(datasetService);

        Deque<ModelResponse> turns = new ArrayDeque<>();
        // GPT-OSS-style: writes its next step as a TOOL_REQUEST-shaped text envelope instead of an
        // actual native tool call on the very first turn.
        turns.add(textTurn("{\"type\":\"TOOL_REQUEST\",\"goal\":\"Create the storeDataset\","
                + "\"reason\":\"Need to extract the store records first.\"}"));
        turns.add(toolCallTurn("storedataset__create_dataset", Map.of(
                "sourceImageCount", 1, "expectedRecordCount", 2, "records", List.of(
                        Map.of("network", "Biedronka", "fullAddress", "Adres 1", "sourceRow", 1),
                        Map.of("network", "Biedronka", "fullAddress", "Adres 2", "sourceRow", 2)))));
        turns.add(textTurn("Zestaw danych utworzony."));
        ScriptedProvider provider = new ScriptedProvider(turns);
        FullToolManager toolManager = new FullToolManager(datasetService, storeDatasetTool);
        RecordingCognitiveEventBus events = new RecordingCognitiveEventBus();

        NativeToolLoopService service = new NativeToolLoopService(
                List.of(provider), toolManager, query -> ToolIntent.NO_TOOL,
                new ToolRuntimeProperties(true, 30, 30, 2, 30, "native", 10, 20),
                events, new ToolRuntimeDebugService(), new ObjectMapper(),
                new NativeToolSchemaMapper(minimalStoreAuditRegistry()), datasetService
        );

        ToolCallingResult result = service.execute(new ToolCallingRequest(
                "request-1", "conversation-1", "wolalbym robic audyty rownomiernie we wtorki i srody",
                "Create a canonical storeDataset with the provided list of stores, then verify, set "
                        + "preferences, geocode addresses, plan routes, submit schedule.",
                "Need to create, verify, set preferences, geocode, plan, and submit the audit schedule",
                "Base prompt",
                new Brain(BrainType.FAST, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.LOW),
                KnowledgeMode.FAST
        ));

        assertThat(result.handled()).isTrue();
        // At least one real storeDataset tool call actually executed - the exact reported bug had
        // toolCalls=0 across all 30 turns.
        assertThat(result.results()).anyMatch(toolResult -> "storedataset".equalsIgnoreCase(toolResult.tool()));
        assertThat(datasetService.findLatestForConversation("conversation-1")).isPresent();
    }

    // TEST 8 (multi-turn data preservation): a store list pasted in an EARLIER turn of the same
    // conversation must still be visible to the native tool loop in a LATER turn whose own
    // goal/message only refers to it in passing ("the provided list") - the exact confirmed gap:
    // NativeToolLoopService built its system prompt from only the CURRENT message, never any earlier
    // conversation turn, so the model had no way to recover the real records except inventing them.
    @Test
    void earlierTurnStoreListStaysVisibleToALaterTurnsNativeToolCall() {
        RecordingCognitiveEventBus events = new RecordingCognitiveEventBus();
        StoreAuditDatasetService datasetService = new StoreAuditDatasetService(events);
        ScriptedProvider provider = new ScriptedProvider(new ArrayDeque<>(List.of(textTurn("Ustawiam preferencje."))));
        NativeToolLoopService service = new NativeToolLoopService(
                List.of(provider), new NoopToolManager(), query -> ToolIntent.NO_TOOL,
                new ToolRuntimeProperties(true, 10, 10, 2, 30, "native", 10, 20),
                events, new ToolRuntimeDebugService(), new ObjectMapper(),
                new NativeToolSchemaMapper(minimalStoreAuditRegistry()), datasetService
        );
        String earlierTurnStoreList = "USER: 1. Biedronka, Ulica Testowa 1, 00-001 Miasto\n"
                + "2. Stokrotka, Ulica Testowa 2, 00-002 Miasto\n"
                + "(...21 more store addresses...)";

        service.execute(new ToolCallingRequest(
                "request-2", "conversation-1", "wolalbym robic to rownomiernie we wtorki i srody",
                "Set even distribution on Tuesdays and Wednesdays for the provided list.", "preferences",
                Map.of(), "Base prompt",
                new Brain(BrainType.FAST, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.LOW),
                KnowledgeMode.FAST, List.of(), earlierTurnStoreList
        ));

        assertThat(provider.capturedMessages()).isNotEmpty();
        List<ModelMessage> firstCallMessages = provider.capturedMessages().get(0);
        assertThat(firstCallMessages).anySatisfy(message ->
                assertThat(message.content()).contains("Biedronka, Ulica Testowa 1", "Stokrotka, Ulica Testowa 2"));
    }

    private static ModelResponse toolCallTurn(String name, Map<String, Object> arguments) {
        return new ModelResponse("", "", List.of(new ModelToolCall("call-" + System.nanoTime(), name, arguments)),
                "tool_calls", new ModelUsage(0, 0, 0));
    }

    private static ModelResponse textTurn(String content) {
        return new ModelResponse(content, "", List.of(), "stop", new ModelUsage(0, 0, 0));
    }

    private static ToolRegistry minimalStoreAuditRegistry() {
        return registry(knowledgeDefinition(), locationDefinition(), storeDatasetDefinition());
    }

    private static ToolRegistry fullMixedRegistryWithRobloxAndWeb() {
        ToolDefinition roblox = new ToolDefinition("mcp_roblox_search_game_tree", "Search Roblox tree.", List.of(
                new ToolOperationDefinition("CALL", "Search.", List.of(
                        new ToolArgumentDefinition("query", "string", true, "Search query")
                ), false, ToolSafetyLevel.READ)
        ));
        ToolDefinition web = new ToolDefinition("web", "Web search", List.of(
                new ToolOperationDefinition("SEARCH_WEB", "Search web", List.of(
                        new ToolArgumentDefinition("query", "string", true, "Search query")
                ), false, ToolSafetyLevel.READ)
        ));
        return registry(knowledgeDefinition(), locationDefinition(), storeDatasetDefinition(), roblox, web);
    }

    private static ToolDefinition knowledgeDefinition() {
        return new ToolDefinition("knowledge", "Knowledge workspace.", List.of(
                new ToolOperationDefinition("READ_DOCUMENT", "Read a document.", List.of(
                        new ToolArgumentDefinition("path", "string", true, "Path")
                ), false, ToolSafetyLevel.READ)
        ));
    }

    private static ToolDefinition locationDefinition() {
        return new ToolDefinition("location", "Geocoding.", List.of(
                new ToolOperationDefinition("GEOCODE_DATASET", "Geocode dataset records.", List.of(
                        new ToolArgumentDefinition("datasetId", "string", false, "Dataset id")
                ), true, ToolSafetyLevel.WRITE)
        ));
    }

    private static ToolDefinition storeDatasetDefinition() {
        return new ToolDefinition("storeDataset", "Canonical dataset.", List.of(
                new ToolOperationDefinition("CREATE_DATASET", "Create dataset.", List.of(
                        new ToolArgumentDefinition("sourceImageCount", "number", true, "Count"),
                        new ToolArgumentDefinition("expectedRecordCount", "number", true, "Expected count"),
                        new ToolArgumentDefinition("records", "array", true, "Records")
                ), true, ToolSafetyLevel.WRITE),
                new ToolOperationDefinition("VERIFY_DATASET", "Verify dataset.", List.of(
                        new ToolArgumentDefinition("datasetId", "string", false, "Dataset id"),
                        new ToolArgumentDefinition("verifications", "array", true, "Verifications")
                ), true, ToolSafetyLevel.WRITE)
        ));
    }

    private static ToolRegistry registry(ToolDefinition... definitions) {
        List<ToolDefinition> values = List.of(definitions);
        return new ToolRegistry() {
            @Override
            public List<ToolDefinition> definitions() {
                return values;
            }

            @Override
            public String promptSection() {
                return "";
            }
        };
    }

    /**
     * Records every published event's metadata, keyed by whether it is a {@code TOOL_LOOP_STARTED}
     * event - used to assert the resolved intent the loop actually started with.
     */
    private static final class RecordingCognitiveEventBus implements CognitiveEventBus {

        private Map<String, Object> lastStartedMetadata;

        String lastStartedResolvedIntent() {
            return lastStartedMetadata == null ? "" : String.valueOf(lastStartedMetadata.get("resolvedIntent"));
        }

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
            if (event == CognitiveEventType.TOOL_LOOP_STARTED) {
                lastStartedMetadata = metadata;
            }
        }
    }

    /**
     * No-op event bus for tests that don't need to inspect published events.
     */
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

    /**
     * Scripted {@link AIProvider} that also records the native tool definitions sent on the most
     * recent {@code toolChat} call, for scope-filtering assertions.
     */
    private static final class ScriptedProvider implements AIProvider {

        private final Deque<ModelResponse> turns;
        private int calls;
        private List<String> lastToolNames = List.of();
        private final List<List<ModelMessage>> capturedMessages = new ArrayList<>();

        private ScriptedProvider(Deque<ModelResponse> turns) {
            this.turns = turns;
        }

        int callCount() {
            return calls;
        }

        List<String> lastToolNames() {
            return lastToolNames;
        }

        List<List<ModelMessage>> capturedMessages() {
            return capturedMessages;
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
            lastToolNames = tools == null ? List.of() : tools.stream().map(NativeToolDefinition::name).toList();
            capturedMessages.add(List.copyOf(messages));
            return turns.isEmpty() ? textTurn("Gotowe.") : turns.poll();
        }
    }

    private static final class NoopToolManager implements ToolManager {

        @Override
        public List<JarvisTool> listTools() {
            return List.of();
        }

        @Override
        public Optional<JarvisTool> findTool(String name) {
            return Optional.empty();
        }

        @Override
        public ToolResult execute(ToolRequest request) {
            throw new UnsupportedOperationException("Not used in this test");
        }
    }

    private static final class KnowledgeOnlyToolManager implements ToolManager {

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
            if ("knowledge".equalsIgnoreCase(request.toolName()) && "READ_DOCUMENT".equalsIgnoreCase(request.operation())) {
                return new ToolResult(true, "knowledge", "READ_DOCUMENT", request.requestId(), request.conversationId(),
                        false, List.of(), "Document read", Map.of("content", "workflow procedure"), "", "", false, "");
            }
            throw new UnsupportedOperationException("Unexpected tool call: " + request.toolName() + "." + request.operation());
        }
    }

    /**
     * Delegates {@code storedataset} calls to a real {@link StoreDatasetTool}.
     */
    private static final class FullToolManager implements ToolManager {

        private final StoreAuditDatasetService datasetService;
        private final StoreDatasetTool storeDatasetTool;

        private FullToolManager(StoreAuditDatasetService datasetService, StoreDatasetTool storeDatasetTool) {
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
            if ("storedataset".equalsIgnoreCase(request.toolName())) {
                return storeDatasetTool.execute(request);
            }
            if ("knowledge".equalsIgnoreCase(request.toolName()) && "READ_DOCUMENT".equalsIgnoreCase(request.operation())) {
                return new ToolResult(true, "knowledge", "READ_DOCUMENT", request.requestId(), request.conversationId(),
                        false, List.of(), "Document read", Map.of("content", "workflow procedure"), "", "", false, "");
            }
            throw new UnsupportedOperationException("Unexpected tool call: " + request.toolName() + "." + request.operation());
        }
    }
}
