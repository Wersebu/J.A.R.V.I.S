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
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class NativeToolLoopServiceTest {

    @Test
    void activeCodingWorkspaceIsInjectedIntoNativeCodingToolCall() {
        Deque<ModelResponse> turns = new ArrayDeque<>();
        turns.add(new ModelResponse("", "", List.of(new ModelToolCall("call-1", "coding__file_read", Map.of(
                "path", "WINDOWS_ONLY.txt"
        ))), "tool_calls", new ModelUsage(0, 0, 0)));
        turns.add(new ModelResponse("Zawartosc WINDOWS_ONLY.txt: windows-only-content", "", List.of(), "stop",
                new ModelUsage(0, 0, 0)));
        CapturingProvider provider = new CapturingProvider(turns);
        CapturingToolManager toolManager = new CapturingToolManager();
        NativeToolLoopService service = new NativeToolLoopService(
                List.of(provider),
                toolManager,
                query -> ToolIntent.CODING_WORKSPACE,
                new ToolRuntimeProperties(true, 4, 4, 1, 30, "native"),
                new NoopCognitiveEventBus(),
                new ToolRuntimeDebugService(),
                new ObjectMapper(),
                new NativeToolSchemaMapper(codingRegistry()),
                new com.jarvis.tools.dataset.StoreAuditDatasetService(new NoopCognitiveEventBus())
        );

        ToolCallingResult result = service.execute(new ToolCallingRequest(
                "request-1",
                "conversation-1",
                "Odczytaj WINDOWS_ONLY.txt i podaj jego zawartosc",
                "Read WINDOWS_ONLY.txt from the active Coding Workspace.",
                "The user asked for a project file from the active Coding Workspace.",
                Map.of(
                        "activeCodingWorkspaceId", "workspace-1",
                        "activeCodingWorkspaceName", "Test",
                        "activeCodingWorkspaceHost", "WINDOWS"
                ),
                "Base prompt",
                new Brain(BrainType.FAST, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.LOW),
                KnowledgeMode.FAST,
                List.of(),
                ""
        ));

        assertThat(result.handled()).isTrue();
        assertThat(result.finalAnswer()).contains("windows-only-content");
        assertThat(provider.toolNames()).contains("coding__file_read", "knowledge__search_content");
        assertThat(toolManager.capturedRequest.get()).isNotNull();
        assertThat(toolManager.capturedRequest.get().toolName()).isEqualTo("coding");
        assertThat(toolManager.capturedRequest.get().operation()).isEqualTo("FILE_READ");
        assertThat(toolManager.capturedRequest.get().arguments())
                .containsEntry("_activeCodingWorkspaceId", "workspace-1")
                .containsEntry("_activeCodingWorkspaceName", "Test")
                .containsEntry("_activeCodingWorkspaceHost", "WINDOWS")
                .containsEntry("path", "WINDOWS_ONLY.txt");
        assertThat(toolManager.knowledgeExecutions).hasValue(0);
    }

    @Test
    void providerToolJsonFailureDoesNotEscapeNativeLoop() {
        AtomicInteger toolChatCalls = new AtomicInteger();
        NativeToolLoopService service = new NativeToolLoopService(
                List.of(new FailingThenFallbackProvider(toolChatCalls)),
                new NoopToolManager(),
                query -> ToolIntent.SEARCH_WEB,
                new ToolRuntimeProperties(true, 2, 2, 1, 30, "native"),
                new NoopCognitiveEventBus(),
                new ToolRuntimeDebugService(),
                new ObjectMapper(),
                new NativeToolSchemaMapper(webRegistry()),
                new com.jarvis.tools.dataset.StoreAuditDatasetService(new NoopCognitiveEventBus())
        );

        ToolCallingResult result = service.execute(new ToolCallingRequest(
                "request-1",
                "conversation-1",
                "po ile sa uzywane RTX 3060 Ti?",
                "Retrieve the current market price for used RTX 3060 Ti cards.",
                "Needs current marketplace evidence.",
                "Base prompt",
                new Brain(BrainType.FAST, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.LOW),
                KnowledgeMode.FAST
        ));

        assertThat(result.handled()).isTrue();
        assertThat(result.finalAnswer()).contains("fallback answer");
        assertThat(result.steps()).anySatisfy(step ->
                assertThat(step.action()).isEqualTo("MODEL_FALLBACK"));
        assertThat(toolChatCalls).hasValue(2);
    }

    private static ToolRegistry webRegistry() {
        ToolDefinition definition = new ToolDefinition("web", "Web search", List.of(
                new ToolOperationDefinition("SEARCH_WEB", "Search web", List.of(
                        new ToolArgumentDefinition("query", "string", true, "Search query"),
                        new ToolArgumentDefinition("maxResults", "integer", false, "Maximum results")
                ), false, ToolSafetyLevel.READ),
                new ToolOperationDefinition("READ_WEB_PAGE", "Read web page", List.of(
                        new ToolArgumentDefinition("url", "string", true, "URL")
                ), false, ToolSafetyLevel.READ)
        ));
        return new ToolRegistry() {
            @Override
            public List<ToolDefinition> definitions() {
                return List.of(definition);
            }

            @Override
            public String promptSection() {
                return "Tool: web SEARCH_WEB";
            }
        };
    }

    private static ToolRegistry codingRegistry() {
        ToolDefinition coding = new ToolDefinition("coding", "Coding Workspace project tools.", List.of(
                new ToolOperationDefinition("FILE_READ", "Read a project file.", List.of(
                        new ToolArgumentDefinition("path", "string", true, "Project-relative path")
                ), false, ToolSafetyLevel.READ),
                new ToolOperationDefinition("FILE_SEARCH", "Search project files.", List.of(
                        new ToolArgumentDefinition("query", "string", true, "Search query")
                ), false, ToolSafetyLevel.READ)
        ));
        ToolDefinition knowledge = new ToolDefinition("knowledge", "Searches saved Knowledge Workspace documents, not project files.", List.of(
                new ToolOperationDefinition("SEARCH_CONTENT", "Search knowledge content.", List.of(
                        new ToolArgumentDefinition("query", "string", true, "Search query")
                ), false, ToolSafetyLevel.READ)
        ));
        return new ToolRegistry() {
            @Override
            public List<ToolDefinition> definitions() {
                return List.of(coding, knowledge);
            }

            @Override
            public String promptSection() {
                return "Tool: coding FILE_READ";
            }
        };
    }

    private static final class FailingThenFallbackProvider implements AIProvider {

        private final AtomicInteger calls;

        private FailingThenFallbackProvider(AtomicInteger calls) {
            this.calls = calls;
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
        public ModelResponse toolChat(
                Brain brain,
                List<ModelMessage> messages,
                List<NativeToolDefinition> tools,
                AIJobType jobType
        ) {
            if (calls.getAndIncrement() == 0) {
                throw new AIProviderException("Ollama native tool chat failed with status 500");
            }
            return new ModelResponse("fallback answer", "", List.of(), "stop", new ModelUsage(0, 0, 0));
        }
    }

    private static final class CapturingProvider implements AIProvider {

        private final Deque<ModelResponse> turns;
        private final List<String> toolNames = new ArrayList<>();

        private CapturingProvider(Deque<ModelResponse> turns) {
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
        public ModelResponse toolChat(
                Brain brain,
                List<ModelMessage> messages,
                List<NativeToolDefinition> tools,
                AIJobType jobType
        ) {
            toolNames.clear();
            toolNames.addAll(tools.stream().map(NativeToolDefinition::name).toList());
            return turns.isEmpty()
                    ? new ModelResponse("", "", List.of(), "stop", new ModelUsage(0, 0, 0))
                    : turns.poll();
        }

        private List<String> toolNames() {
            return toolNames;
        }
    }

    private static final class CapturingToolManager implements ToolManager {

        private final AtomicReference<ToolRequest> capturedRequest = new AtomicReference<>();
        private final AtomicInteger knowledgeExecutions = new AtomicInteger();

        @Override
        public List<JarvisTool> listTools() {
            return List.of();
        }

        @Override
        public Optional<JarvisTool> findTool(String name) {
            String normalized = name == null ? "" : name.toLowerCase(Locale.ROOT);
            if ("coding".equals(normalized) || "knowledge".equals(normalized)) {
                return Optional.of(new JarvisTool() {
                    @Override
                    public String getName() {
                        return normalized;
                    }

                    @Override
                    public String getDescription() {
                        return normalized;
                    }

                    @Override
                    public ToolResult execute(ToolRequest request) {
                        return CapturingToolManager.this.execute(request);
                    }
                });
            }
            return Optional.empty();
        }

        @Override
        public ToolResult execute(ToolRequest request) {
            if ("knowledge".equalsIgnoreCase(request.toolName())) {
                knowledgeExecutions.incrementAndGet();
                throw new AssertionError("KnowledgeTool must not be used for active Coding Workspace files");
            }
            capturedRequest.set(request);
            return new ToolResult(true, "coding", "FILE_READ", request.requestId(), request.conversationId(),
                    false, List.of("coding-workspace:" + request.arguments().get("_activeCodingWorkspaceId")),
                    "Coding FILE_READ finished", Map.of(
                    "workspaceId", request.arguments().get("_activeCodingWorkspaceId"),
                    "result", Map.of("path", "WINDOWS_ONLY.txt", "content", "windows-only-content")
            ), "", "", false, "");
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
            throw new AssertionError("Tool execution should not be reached");
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
