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
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test proving a tool implementation that throws (validation failure, IO error, ...)
 * never blows past the whole tool loop. Before this fix, an unguarded
 * {@code toolManager.execute(...)} call let an unchecked exception propagate all the way up
 * through {@code ToolCallingStage} and {@code CognitivePipelineExecutor}, terminating the request
 * with a generic error and giving the model no chance to see what failed or retry.
 */
class NativeToolLoopServiceExecutionFailureTest {

    @Test
    void toolExecutionExceptionBecomesAFailedResultTheModelCanReactTo() {
        Deque<ModelResponse> turns = new ArrayDeque<>();
        turns.add(toolCallTurn("knowledge__create_document", Map.of("path", "notes/../../etc", "content", "x")));
        turns.add(textTurn("Nie udalo sie zapisac dokumentu z powodu nieprawidlowej sciezki."));
        ScriptedProvider provider = new ScriptedProvider(turns);

        ThrowingToolManager toolManager = new ThrowingToolManager();

        NativeToolLoopService service = new NativeToolLoopService(
                List.of(provider), toolManager, query -> ToolIntent.NO_TOOL,
                new ToolRuntimeProperties(true, 4, 8, 2, 30, "native"),
                new NoopCognitiveEventBus(), new ToolRuntimeDebugService(), new ObjectMapper(),
                new NativeToolSchemaMapper(registry())
        );

        ToolCallingResult result = service.execute(new ToolCallingRequest(
                "request-1", "conversation-1", "Zapisz notatke",
                "Save a note", "test", "Base prompt",
                new Brain(BrainType.FAST, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.LOW),
                KnowledgeMode.FAST
        ));

        assertThat(result.handled()).isTrue();
        assertThat(result.finalAnswer()).isEqualTo("Nie udalo sie zapisac dokumentu z powodu nieprawidlowej sciezki.");
        assertThat(toolManager.executeCallCount()).isEqualTo(1);

        assertThat(result.results()).hasSize(1);
        ToolResult failed = result.results().get(0);
        assertThat(failed.success()).isFalse();
        assertThat(failed.errorCode()).isEqualTo("TOOL_EXECUTION_FAILED");
        assertThat(failed.errorMessage()).contains("Path traversal is not allowed");
    }

    private static ModelResponse toolCallTurn(String name, Map<String, Object> arguments) {
        return new ModelResponse("", "", List.of(new ModelToolCall("call-" + name + "-" + System.nanoTime(), name, arguments)),
                "tool_calls", new ModelUsage(0, 0, 0));
    }

    private static ModelResponse textTurn(String content) {
        return new ModelResponse(content, "", List.of(), "stop", new ModelUsage(0, 0, 0));
    }

    private static ToolRegistry registry() {
        ToolDefinition knowledge = new ToolDefinition("knowledge", "Manages the Knowledge Workspace.", List.of(
                new ToolOperationDefinition("CREATE_DOCUMENT", "Create a document.", List.of(
                        new ToolArgumentDefinition("path", "string", true, "Logical path"),
                        new ToolArgumentDefinition("content", "string", true, "Document content")
                ), true, ToolSafetyLevel.WRITE)
        ));
        return new ToolRegistry() {
            @Override
            public List<ToolDefinition> definitions() {
                return List.of(knowledge);
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

    private static final class ThrowingToolManager implements ToolManager {

        private final List<ToolRequest> executed = new ArrayList<>();

        int executeCallCount() {
            return executed.size();
        }

        @Override
        public List<JarvisTool> listTools() {
            return List.of();
        }

        @Override
        public Optional<JarvisTool> findTool(String name) {
            return "knowledge".equalsIgnoreCase(name) ? Optional.of(new PlaceholderTool(name)) : Optional.empty();
        }

        @Override
        public ToolResult execute(ToolRequest request) {
            executed.add(request);
            throw new IllegalArgumentException("Path traversal is not allowed: " + request.arguments().get("path"));
        }
    }

    private static final class PlaceholderTool implements JarvisTool {

        private final String name;

        private PlaceholderTool(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
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
