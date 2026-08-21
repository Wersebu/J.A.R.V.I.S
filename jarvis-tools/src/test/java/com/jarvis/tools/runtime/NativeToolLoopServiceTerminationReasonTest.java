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
import com.jarvis.tools.dataset.StoreAuditDatasetService;
import com.jarvis.tools.schema.ToolArgumentDefinition;
import com.jarvis.tools.schema.ToolDefinition;
import com.jarvis.tools.schema.ToolOperationDefinition;
import com.jarvis.tools.schema.ToolRegistry;
import com.jarvis.tools.schema.ToolSafetyLevel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link NativeToolLoopService} attaches a real, structural {@link
 * ToolLoopTerminationInfo} at every return point - never guessed from the model's own text - and
 * correctly distinguishes model turns from executed tool calls, and a single non-terminal tool
 * failure from a genuinely terminal one.
 */
class NativeToolLoopServiceTerminationReasonTest {

    @Test
    void normalCompletionReportsCompletedWithGoalSatisfiedAndRealCounts() {
        QueueProvider provider = new QueueProvider(List.of(
                toolCallTurn("web", "SEARCH_WEB", Map.of("query", "stan polaczonego systemu")),
                textTurn("System dziala poprawnie, wszystkie sprawdzone elementy sa zgodne z oczekiwaniami.")
        ));
        NativeToolLoopService service = newService(provider, webRegistry(), new ScriptedToolManager(true), 10, 10, 30);

        ToolCallingResult result = service.execute(request("sprawdz stan polaczonego systemu"));

        ToolLoopTerminationInfo info = result.terminationInfo();
        assertThat(info.terminationReason()).isEqualTo(ToolLoopTerminationReason.COMPLETED);
        assertThat(info.completed()).isTrue();
        assertThat(info.goalSatisfied()).isTrue();
        assertThat(info.usedModelTurns()).isEqualTo(2);
        assertThat(info.executedToolCalls()).isEqualTo(1);
        assertThat(info.successfulToolCalls()).isEqualTo(1);
        assertThat(info.failedToolCalls()).isZero();
    }

    // Regression coverage for the reported bug: the turn/call budget must be reported as genuinely
    // exhausted (real successful tool calls, no timeout, no empty-response marker) - never confused
    // with the number of individual native tool_calls, which can exceed the turn count.
    @Test
    void turnBudgetExhaustionReportsMaxTurnsReachedWithRealTurnVsToolCallCounts() {
        // resolvedIntent=SEARCH_WEB carries its own >=12-turn floor (unrelated to this test), so the
        // configured budget here is deliberately set to exactly that floor rather than fighting it -
        // what matters is that usedModelTurns/executedToolCalls end up equal to the real budget, not
        // any particular small number.
        NativeToolLoopService service = newService(new IncrementingSearchProvider(),
                new com.jarvis.tools.ToolRuntimeProperties(true, 12, 12, 2, 30, "native", 100, 20),
                webRegistry(), new ScriptedToolManager(true));

        ToolCallingResult result = service.execute(request("zbadaj dokladnie ten temat"));

        ToolLoopTerminationInfo info = result.terminationInfo();
        assertThat(info.terminationReason()).isEqualTo(ToolLoopTerminationReason.MAX_TURNS_REACHED);
        assertThat(info.completed()).isFalse();
        assertThat(info.goalSatisfied()).isFalse();
        assertThat(info.configuredMaxTurns()).isEqualTo(12);
        assertThat(info.usedModelTurns()).isEqualTo(12);
        assertThat(info.executedToolCalls()).isEqualTo(12);
        assertThat(info.successfulToolCalls()).isEqualTo(12);
        assertThat(info.failedToolCalls()).isZero();
    }

    @Test
    void timeoutReportsTimeoutReasonNotMaxTurnsReached() {
        NativeToolLoopService service = newService(new SlowThenToolCallProvider(), webRegistry(),
                new ScriptedToolManager(true), 30, 30, 1);

        ToolCallingResult result = service.execute(request("zbadaj to bardzo dokladnie"));

        ToolLoopTerminationInfo info = result.terminationInfo();
        assertThat(info.terminationReason()).isEqualTo(ToolLoopTerminationReason.TIMEOUT);
        assertThat(info.completed()).isFalse();
        assertThat(info.elapsedMs()).isGreaterThan(1_000L);
        assertThat(info.usedModelTurns()).isLessThan(30);
    }

    @Test
    void repeatedEmptyModelTurnsReportEmptyModelResponse() {
        QueueProvider provider = new QueueProvider(List.of(
                textTurnBlank(), textTurnBlank(), textTurnBlank(), textTurnBlank()
        ));
        NativeToolLoopService service = newService(provider, webRegistry(), new ScriptedToolManager(true), 10, 10, 30);

        ToolCallingResult result = service.execute(request("cos"));

        assertThat(result.terminationInfo().terminationReason()).isEqualTo(ToolLoopTerminationReason.EMPTY_MODEL_RESPONSE);
        assertThat(result.terminationInfo().completed()).isFalse();
    }

    // A native tool call that requires human approval must stop the loop immediately and report
    // WAITING_FOR_APPROVAL - never as if the loop simply ran out of turns.
    @Test
    void approvalRequiredReportsWaitingForApproval() {
        QueueProvider provider = new QueueProvider(List.of(
                toolCallTurn("web", "SEARCH_WEB", Map.of("query", "cos"))
        ));
        NativeToolLoopService service = newService(provider, webRegistry(), new ScriptedToolManager(false, true), 10, 10, 30);

        ToolCallingResult result = service.execute(request("zrob to"));

        ToolLoopTerminationInfo info = result.terminationInfo();
        assertThat(info.terminationReason()).isEqualTo(ToolLoopTerminationReason.WAITING_FOR_APPROVAL);
        assertThat(info.completed()).isFalse();
    }

    // Every executed call this loop was an MCP call and every single one failed - zero forward
    // progress. This is the one case where a tool-level failure, not the turn budget, is honestly
    // the real story.
    @Test
    void everyExecutedMcpCallFailingReportsMcpFailure() {
        QueueProvider provider = new QueueProvider(List.of(
                toolCallTurn("mcp_test_tool", "CALL", Map.of()),
                toolCallTurn("mcp_test_tool", "CALL", Map.of()),
                toolCallTurn("mcp_test_tool", "CALL", Map.of())
        ));
        NativeToolLoopService service = newService(provider, mcpRegistry(), new ScriptedToolManager(false), 3, 3, 30);

        ToolCallingResult result = service.execute(request("napraw skrypt"));

        ToolLoopTerminationInfo info = result.terminationInfo();
        assertThat(info.terminationReason()).isEqualTo(ToolLoopTerminationReason.MCP_FAILURE);
        assertThat(info.lastToolName()).isEqualTo("mcp_test_tool");
        assertThat(info.lastErrorMessage()).isNotBlank();
    }

    // The exact scenario the bug report described: one MCP call fails, but the loop keeps making
    // real progress afterward - the failure must surface only as diagnostic (lastErrorMessage), and
    // the termination reason must stay MAX_TURNS_REACHED, never MCP_FAILURE.
    @Test
    void oneFailedMcpCallFollowedByRealProgressStillReportsMaxTurnsReached() {
        QueueProvider provider = new QueueProvider(List.of(
                toolCallTurn("mcp_test_tool", "CALL", Map.of())
        ));
        provider.thenRepeat(new IncrementingSearchProvider());
        NativeToolLoopService service = newService(provider,
                new com.jarvis.tools.ToolRuntimeProperties(true, 12, 12, 2, 30, "native", 100, 20),
                mixedRegistry(), new ScriptedToolManager(true, false, false));

        ToolCallingResult result = service.execute(request("napraw i przetestuj wioski"));

        ToolLoopTerminationInfo info = result.terminationInfo();
        assertThat(info.terminationReason()).isEqualTo(ToolLoopTerminationReason.MAX_TURNS_REACHED);
        assertThat(info.successfulToolCalls()).isGreaterThan(0);
        assertThat(info.failedToolCalls()).isEqualTo(1);
        // The one MCP failure is still visible as diagnostic context (lastErrorMessage) even though
        // it was not the reason the loop stopped - it must never be hidden just because later calls
        // succeeded.
        assertThat(info.lastErrorMessage()).isEqualTo("Server datamodel is not available in Edit mode.");
    }

    private NativeToolLoopService newService(AIProvider provider, ToolRegistry registry, ToolManager toolManager,
            int maxCallsFast, int maxCallsResearch, int timeoutSeconds) {
        return newService(provider, new com.jarvis.tools.ToolRuntimeProperties(true, maxCallsFast, maxCallsResearch,
                2, timeoutSeconds, "native"), registry, toolManager);
    }

    private NativeToolLoopService newService(AIProvider provider, com.jarvis.tools.ToolRuntimeProperties properties,
            ToolRegistry registry, ToolManager toolManager) {
        return new NativeToolLoopService(
                List.of(provider), toolManager, query -> ToolIntent.SEARCH_WEB, properties,
                new NoopCognitiveEventBus(), new ToolRuntimeDebugService(), new ObjectMapper(),
                new NativeToolSchemaMapper(registry), new StoreAuditDatasetService(new NoopCognitiveEventBus())
        );
    }

    private ToolCallingRequest request(String message) {
        return new ToolCallingRequest(
                "request-1", "conversation-1", message,
                "Answer: " + message, "test", "Base prompt",
                new Brain(BrainType.FAST, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.LOW),
                KnowledgeMode.FAST
        );
    }

    private static ToolRegistry webRegistry() {
        ToolDefinition definition = new ToolDefinition("web", "Web search", List.of(
                new ToolOperationDefinition("SEARCH_WEB", "Search web", List.of(
                        new ToolArgumentDefinition("query", "string", true, "Search query")
                ), false, ToolSafetyLevel.READ)
        ));
        return simpleRegistry(List.of(definition));
    }

    private static ToolRegistry mcpRegistry() {
        ToolDefinition definition = new ToolDefinition("mcp_test_tool", "MCP tool.", List.of(
                new ToolOperationDefinition("CALL", "Call MCP tool.", List.of(), false, ToolSafetyLevel.READ)
        ));
        return simpleRegistry(List.of(definition));
    }

    private static ToolRegistry mixedRegistry() {
        ToolDefinition mcp = new ToolDefinition("mcp_test_tool", "MCP tool.", List.of(
                new ToolOperationDefinition("CALL", "Call MCP tool.", List.of(), false, ToolSafetyLevel.READ)
        ));
        ToolDefinition web = new ToolDefinition("web", "Web search", List.of(
                new ToolOperationDefinition("SEARCH_WEB", "Search web", List.of(
                        new ToolArgumentDefinition("query", "string", true, "Search query")
                ), false, ToolSafetyLevel.READ)
        ));
        return simpleRegistry(List.of(mcp, web));
    }

    private static ToolRegistry simpleRegistry(List<ToolDefinition> definitions) {
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

    private static ModelResponse toolCallTurn(String tool, String operation, Map<String, Object> arguments) {
        return new ModelResponse("", "", List.of(new ModelToolCall("call-" + System.nanoTime(), tool, arguments)),
                "tool_calls", new ModelUsage(0, 0, 0));
    }

    private static ModelResponse textTurn(String content) {
        return new ModelResponse(content, "", List.of(), "stop", new ModelUsage(0, 0, 0));
    }

    private static ModelResponse textTurnBlank() {
        return new ModelResponse("", "", List.of(), "stop", new ModelUsage(0, 0, 0));
    }

    /** Plays back a fixed queue of turns, repeating the last one once exhausted. */
    private static class QueueProvider implements AIProvider {

        private final List<ModelResponse> turns;
        private AIProvider overflow;
        private int calls;

        QueueProvider(List<ModelResponse> turns) {
            this.turns = turns;
        }

        void thenRepeat(AIProvider overflow) {
            this.overflow = overflow;
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
            if (calls < turns.size()) {
                return turns.get(calls++);
            }
            calls++;
            if (overflow != null) {
                return overflow.toolChat(brain, messages, tools, jobType);
            }
            return turns.get(turns.size() - 1);
        }
    }

    /** Always returns a new SEARCH_WEB call with a distinct query, so it is never blocked as a duplicate. */
    private static class IncrementingSearchProvider implements AIProvider {

        private int calls;

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
            return toolCallTurn("web", "SEARCH_WEB", Map.of("query", "temat czesc " + calls));
        }
    }

    /** Sleeps past the configured timeout before returning a tool call, so the loop's own timeout check fires. */
    private static class SlowThenToolCallProvider implements AIProvider {

        private int calls;

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
            try {
                Thread.sleep(1_100);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return toolCallTurn("web", "SEARCH_WEB", Map.of("query", "temat czesc " + calls));
        }
    }

    /**
     * Executes {@code web.SEARCH_WEB} and, depending on configuration, {@code mcp_test_tool.CALL}
     * with canned success/failure/approval-required results.
     */
    private static class ScriptedToolManager implements ToolManager {

        private final boolean webSucceeds;
        private final boolean requiresApproval;
        private final boolean mcpSucceeds;

        ScriptedToolManager(boolean webSucceeds) {
            this(webSucceeds, false);
        }

        ScriptedToolManager(boolean webSucceeds, boolean requiresApproval) {
            this(webSucceeds, requiresApproval, false);
        }

        ScriptedToolManager(boolean webSucceeds, boolean requiresApproval, boolean mcpSucceeds) {
            this.webSucceeds = webSucceeds;
            this.requiresApproval = requiresApproval;
            this.mcpSucceeds = mcpSucceeds;
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
                    return "";
                }

                @Override
                public ToolResult execute(ToolRequest request) {
                    return ScriptedToolManager.this.execute(request);
                }
            });
        }

        @Override
        public ToolResult execute(ToolRequest request) {
            if ("mcp_test_tool".equals(request.toolName())) {
                if (mcpSucceeds) {
                    return new ToolResult(true, request.toolName(), request.operation(), request.requestId(),
                            request.conversationId(), false, List.of(), "MCP call finished", Map.of(), "", "", false, "");
                }
                return new ToolResult(false, request.toolName(), request.operation(), request.requestId(),
                        request.conversationId(), false, List.of(), "MCP call failed", Map.of(),
                        "MCP_ERROR", "Server datamodel is not available in Edit mode.", false, "");
            }
            if (requiresApproval) {
                return new ToolResult(true, request.toolName(), request.operation(), request.requestId(),
                        request.conversationId(), false, List.of(), "Draft ready", Map.of(), "", "", true, "draft-1");
            }
            if (webSucceeds) {
                return new ToolResult(true, request.toolName(), request.operation(), request.requestId(),
                        request.conversationId(), false, List.of(), "Web search finished",
                        Map.of("results", List.of()), "", "", false, "");
            }
            return new ToolResult(false, request.toolName(), request.operation(), request.requestId(),
                    request.conversationId(), false, List.of(), "Web search failed", Map.of(),
                    "WEB_ERROR", "Search provider unavailable.", false, "");
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
