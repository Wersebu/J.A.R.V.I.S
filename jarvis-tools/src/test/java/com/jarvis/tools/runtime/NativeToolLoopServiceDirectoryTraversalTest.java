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
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class NativeToolLoopServiceDirectoryTraversalTest {

    @Test
    void successfulDifferentDirectoriesDoNotExhaustNoProgressBudget() {
        Deque<ModelResponse> turns = new ArrayDeque<>();
        for (int i = 0; i < 7; i++) {
            turns.add(toolCallTurn("coding__file_list", Map.of("path", "graphics/folder" + i)));
        }
        turns.add(textTurn("Found shield.swf; this is only a filename candidate, not verified content."));
        FakeToolManager manager = new FakeToolManager(new ToolResult(true, "coding", "FILE_LIST", "", "",
                false, List.of(), "Listed directory", Map.of("files", List.of("shield.swf")), "", "", false, ""));
        NativeToolLoopService service = new NativeToolLoopService(
                List.of(new ScriptedProvider(turns)), manager, query -> ToolIntent.LOCATION,
                new ToolRuntimeProperties(true, 12, 12, 2, 30, "native", 3),
                new NoopCognitiveEventBus(), new ToolRuntimeDebugService(), new ObjectMapper(),
                new NativeToolSchemaMapper(codingRegistry()),
                new com.jarvis.tools.dataset.StoreAuditDatasetService(new NoopCognitiveEventBus()));
        var result = service.execute(new ToolCallingRequest("req", "conv", "List files in graphics folders",
                "List filenames", "test", "Base prompt",
                new Brain(BrainType.FAST, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.LOW), KnowledgeMode.FAST));
        assertThat(manager.executedCount()).isEqualTo(7);
        assertThat(result.steps()).noneMatch(step -> "NO_PROGRESS_BLOCKED".equals(step.action()));
        assertThat(result.finalAnswer()).contains("shield.swf").doesNotContain("Do not answer yet");
    }

    private static ModelResponse toolCallTurn(String name, Map<String, Object> arguments) {
        return new ModelResponse("", "", List.of(new ModelToolCall("call-" + System.nanoTime(), name, arguments)),
                "tool_calls", new ModelUsage(0, 0, 0));
    }

    private static ModelResponse textTurn(String content) {
        return new ModelResponse(content, "", List.of(), "stop", new ModelUsage(0, 0, 0));
    }

    private static ToolRegistry codingRegistry() {
        ToolDefinition definition = new ToolDefinition("coding", "Directory listing", List.of(
                new ToolOperationDefinition("FILE_LIST", "List directory", List.of(
                        new ToolArgumentDefinition("path", "string", true, "Directory path")
                ), false, ToolSafetyLevel.READ)
        ));
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

    private static final class FakeToolManager implements ToolManager {

        private final ToolResult scriptedResult;
        private int executedCount;

        private FakeToolManager(ToolResult scriptedResult) {
            this.scriptedResult = scriptedResult;
        }

        int executedCount() {
            return executedCount;
        }

        @Override
        public List<JarvisTool> listTools() {
            return List.of();
        }

        @Override
        public Optional<JarvisTool> findTool(String name) {
            return "coding".equalsIgnoreCase(name) ? Optional.of(new PlaceholderCodingTool()) : Optional.empty();
        }

        @Override
        public ToolResult execute(ToolRequest request) {
            executedCount++;
            return scriptedResult;
        }
    }

    private static final class PlaceholderCodingTool implements JarvisTool {

        @Override
        public String getName() {
            return "coding";
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
