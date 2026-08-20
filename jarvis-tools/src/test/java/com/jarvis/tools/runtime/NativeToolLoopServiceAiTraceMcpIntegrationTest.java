package com.jarvis.tools.runtime;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import com.jarvis.common.trace.AiTraceSettings;
import com.jarvis.tools.JarvisTool;
import com.jarvis.tools.ToolManager;
import com.jarvis.tools.ToolRequest;
import com.jarvis.tools.ToolResult;
import com.jarvis.tools.ToolRuntimeProperties;
import com.jarvis.tools.dataset.StoreAuditDatasetService;
import com.jarvis.tools.mcp.DefaultMcpClientFactory;
import com.jarvis.tools.mcp.DefaultMcpServerManager;
import com.jarvis.tools.mcp.McpAccessLevel;
import com.jarvis.tools.mcp.McpCallResult;
import com.jarvis.tools.mcp.McpContentItem;
import com.jarvis.tools.mcp.McpExecutionHost;
import com.jarvis.tools.mcp.McpJarvisTool;
import com.jarvis.tools.mcp.McpProperties;
import com.jarvis.tools.mcp.McpSchemaMapper;
import com.jarvis.tools.mcp.McpServerProperties;
import com.jarvis.tools.mcp.McpToolDescriptor;
import com.jarvis.tools.mcp.McpTransport;
import com.jarvis.tools.mcp.WindowsMcpBridgeGateway;
import com.jarvis.tools.schema.ToolDefinition;
import com.jarvis.tools.schema.ToolRegistry;
import com.jarvis.tools.schema.ToolSchemaProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end regression test for the full diagnostic AI/tool trace against the REAL MCP call path
 * (real {@link DefaultMcpServerManager}/{@link McpJarvisTool}, not a stand-in {@code ToolManager}
 * that skips straight to a canned {@link ToolResult}) - covers the exact scenario from the feature
 * request: "sprawdz Workspace przez Roblox MCP".
 *
 * <p>Proves: (1) the model-facing tool name ({@code mcp_roblox_search_game_tree}) and the real MCP
 * tool name ({@code search_game_tree}) are both visible and distinguishable in the trace, (2) the
 * real MCP transport boundary ({@link DefaultMcpServerManager#call}) receives the real tool name,
 * never the prefixed one, and (3) the tool-loop turn number threads correctly across two AI
 * requests (turn=1 for the tool call, turn=2 for the follow-up carrying the tool result).</p>
 */
class NativeToolLoopServiceAiTraceMcpIntegrationTest {

    private static final String MODEL_FACING_NAME = "mcp_roblox_search_game_tree";
    private static final String REAL_MCP_TOOL_NAME = "search_game_tree";

    private ListAppender<ILoggingEvent> traceAppender;

    @AfterEach
    void tearDown() {
        AiTraceSettings.reset();
        if (traceAppender != null) {
            Logger logger = (Logger) LoggerFactory.getLogger("AI_TRACE");
            logger.detachAppender(traceAppender);
            traceAppender.stop();
        }
    }

    @Test
    void fullTraceCoversUserToModelToMcpToResultToFinalAnswer() {
        traceAppender = attachTraceAppender();
        AiTraceSettings.configure(true, true, true);

        ScriptedWindowsBridgeGateway gateway = new ScriptedWindowsBridgeGateway();
        Map<String, Object> inputSchema = Map.of(
                "type", "object",
                "properties", Map.of("query", Map.of("type", "string", "description", "Search query")),
                "required", List.of("query"));
        gateway.scriptedToolLists.add(List.of(
                new McpToolDescriptor("roblox", REAL_MCP_TOOL_NAME, "Search the game tree.", inputSchema, McpAccessLevel.READ)));
        gateway.callResult = new McpCallResult(true, List.of(new McpContentItem("text", "Workspace", "", "", Map.of())),
                Map.of("folders", List.of("Workspace", "ReplicatedStorage")), "", "");

        DefaultMcpServerManager manager = manager(gateway);
        List<McpToolDescriptor> discovered = manager.discoverTools();
        assertThat(discovered).hasSize(1);
        McpJarvisTool mcpTool = new McpJarvisTool(discovered.get(0), manager, new McpSchemaMapper());

        ToolManager toolManager = new SingleMcpToolManager(mcpTool);
        ToolRegistry registry = registryFor(mcpTool);

        Deque<ModelResponse> turns = new ArrayDeque<>();
        turns.add(toolCallTurn(MODEL_FACING_NAME + "__call", Map.of("query", "Workspace")));
        turns.add(textTurn("Znaleziono folder Workspace w drzewie gry."));
        TurnRecordingProvider provider = new TurnRecordingProvider(turns);

        StoreAuditDatasetService datasetService = new StoreAuditDatasetService(new NoopCognitiveEventBus());
        NativeToolLoopService service = new NativeToolLoopService(
                List.of(provider), toolManager, query -> ToolIntent.SEARCH_WEB,
                new ToolRuntimeProperties(true, 15, 15, 2, 30, "native", 10, 20),
                new NoopCognitiveEventBus(), new ToolRuntimeDebugService(), new ObjectMapper(),
                new NativeToolSchemaMapper(registry), datasetService
        );

        ToolCallingResult result = service.execute(new ToolCallingRequest(
                "request-1", "conversation-1", "sprawdz Workspace przez Roblox MCP",
                "Explore the Roblox Studio project tree.", "test", "Base prompt",
                new Brain(BrainType.FAST, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.LOW),
                KnowledgeMode.FAST
        ));

        assertThat(result.finalAnswer()).isEqualTo("Znaleziono folder Workspace w drzewie gry.");

        // Turn threading: the tool-call turn ran at turn=1, the follow-up carrying the tool result
        // ran at turn=2 - proves NativeToolLoopService correctly advances AiTraceTurnContext across
        // real tool-loop iterations, which OllamaProvider reads for the "turn=" trace field.
        assertThat(provider.observedTurns).containsExactly(1, 2);

        // The real MCP transport boundary received the real tool name, never the prefixed one.
        assertThat(gateway.calledToolNames).containsExactly(REAL_MCP_TOOL_NAME);

        List<String> logs = traceAppender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();

        String modelToolCall = findLog(logs, "MODEL TOOL CALL");
        assertThat(modelToolCall).contains("tool=" + MODEL_FACING_NAME + "__call");
        assertThat(modelToolCall).contains("turn=1");
        assertThat(modelToolCall).contains("\"query\" : \"Workspace\"");

        String toolExecutionBegin = findLog(logs, "TOOL EXECUTION BEGIN");
        assertThat(toolExecutionBegin).contains("source=MCP");
        assertThat(toolExecutionBegin).contains("mcpServer=roblox");

        String mcpCallBegin = findLog(logs, "MCP CALL BEGIN");
        assertThat(mcpCallBegin).contains("server=roblox");
        // The model calls the native-function-shaped mcp_roblox_search_game_tree__call, but the
        // MCP boundary itself only ever knows the bare descriptor name (no __call suffix - that
        // suffix is purely the native tool-calling function-name convention) and the real tool name.
        assertThat(mcpCallBegin).contains("modelFacingTool=" + MODEL_FACING_NAME);
        assertThat(mcpCallBegin).contains("mcpTool=" + REAL_MCP_TOOL_NAME);

        String toolResult = findLog(logs, "TOOL RESULT");
        assertThat(toolResult).contains("success=true");

        // Ordering: the model's tool call must be logged before the MCP boundary is crossed, which
        // must be logged before the result - exactly USER -> MODEL -> TOOL_CALL -> MCP -> TOOL_RESULT.
        assertThat(logs.indexOf(modelToolCall))
                .isLessThan(logs.indexOf(toolExecutionBegin))
                .isLessThan(logs.indexOf(mcpCallBegin))
                .isLessThan(logs.indexOf(toolResult));
    }

    private String findLog(List<String> logs, String marker) {
        return logs.stream().filter(message -> message.contains(marker)).findFirst()
                .orElseThrow(() -> new AssertionError("No trace log containing \"" + marker + "\" was captured. All logs: " + logs));
    }

    private ListAppender<ILoggingEvent> attachTraceAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger("AI_TRACE");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private DefaultMcpServerManager manager(ScriptedWindowsBridgeGateway gateway) {
        McpProperties properties = new McpProperties();
        properties.setEnabled(true);
        properties.setServers(Map.of("roblox", windowsBridgeServer()));
        return new DefaultMcpServerManager(
                properties,
                new DefaultMcpClientFactory(new ObjectMapper(), "test", provider(gateway)),
                provider(gateway)
        );
    }

    private McpServerProperties windowsBridgeServer() {
        McpServerProperties properties = new McpServerProperties();
        properties.setEnabled(true);
        properties.setExecutionHost(McpExecutionHost.WINDOWS);
        properties.setTransport(McpTransport.WINDOWS_BRIDGE);
        properties.setCommand("cmd.exe");
        properties.setArgs(List.of("/c", "cd /d %LOCALAPPDATA%\\Roblox && .\\mcp.bat"));
        return properties;
    }

    private ObjectProvider<WindowsMcpBridgeGateway> provider(WindowsMcpBridgeGateway gateway) {
        return new ObjectProvider<>() {
            @Override
            public WindowsMcpBridgeGateway getObject(Object... args) {
                return gateway;
            }

            @Override
            public WindowsMcpBridgeGateway getIfAvailable() {
                return gateway;
            }

            @Override
            public WindowsMcpBridgeGateway getIfUnique() {
                return gateway;
            }

            @Override
            public WindowsMcpBridgeGateway getObject() {
                return gateway;
            }
        };
    }

    private ToolRegistry registryFor(McpJarvisTool tool) {
        ToolDefinition definition = ((ToolSchemaProvider) tool).definition();
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

    private static ModelResponse toolCallTurn(String name, Map<String, Object> arguments) {
        return new ModelResponse("", "", List.of(new ModelToolCall("call-" + System.nanoTime(), name, arguments)),
                "tool_calls", new ModelUsage(0, 0, 0));
    }

    private static ModelResponse textTurn(String content) {
        return new ModelResponse(content, "", List.of(), "stop", new ModelUsage(0, 0, 0));
    }

    /** Routes every call to the single wrapped {@link McpJarvisTool} - a real MCP execution path. */
    private static final class SingleMcpToolManager implements ToolManager {

        private final McpJarvisTool tool;

        private SingleMcpToolManager(McpJarvisTool tool) {
            this.tool = tool;
        }

        @Override
        public List<JarvisTool> listTools() {
            return List.of(tool);
        }

        @Override
        public Optional<JarvisTool> findTool(String name) {
            return tool.getName().equalsIgnoreCase(name) ? Optional.of(tool) : Optional.empty();
        }

        @Override
        public ToolResult execute(ToolRequest request) {
            return tool.execute(request);
        }
    }

    /**
     * Scripted {@link WindowsMcpBridgeGateway} - records the real MCP tool name every {@code
     * callTool} was invoked with, so the test can prove the wire-level call never uses the
     * model-facing prefixed name.
     */
    private static final class ScriptedWindowsBridgeGateway implements WindowsMcpBridgeGateway {

        private final Deque<List<McpToolDescriptor>> scriptedToolLists = new ArrayDeque<>();
        private final List<String> calledToolNames = new ArrayList<>();
        private McpCallResult callResult = new McpCallResult(true, List.of(), Map.of(), "", "");

        @Override
        public boolean isBridgeConnected() {
            return true;
        }

        @Override
        public void initialize(String serverId, McpServerProperties properties, String clientVersion) {
        }

        @Override
        public List<McpToolDescriptor> listTools(String serverId, McpServerProperties properties) {
            return scriptedToolLists.isEmpty() ? List.of() : scriptedToolLists.poll();
        }

        @Override
        public McpCallResult callTool(String serverId, String toolName, Map<String, Object> arguments, Duration timeout) {
            calledToolNames.add(toolName);
            return callResult;
        }

        @Override
        public void disconnect(String serverId) {
        }

        @Override
        public String bridgeStatus() {
            return "CONNECTED";
        }
    }

    /** Records {@link com.jarvis.common.trace.AiTraceTurnContext#current()} at every provider call. */
    private static final class TurnRecordingProvider implements AIProvider {

        private final Deque<ModelResponse> turns;
        private final List<Integer> observedTurns = new ArrayList<>();

        private TurnRecordingProvider(Deque<ModelResponse> turns) {
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
            observedTurns.add(com.jarvis.common.trace.AiTraceTurnContext.current());
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
