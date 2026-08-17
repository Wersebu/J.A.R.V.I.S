package com.jarvis.tools.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.common.ai.AIJobType;
import com.jarvis.common.ai.AIProvider;
import com.jarvis.common.ai.AIProviderException;
import com.jarvis.common.ai.Brain;
import com.jarvis.common.ai.BrainType;
import com.jarvis.common.ai.ModelMessage;
import com.jarvis.common.ai.ModelResponse;
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

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class NativeToolLoopServiceTest {

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
