package com.jarvis.tools.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.common.ai.AIJobType;
import com.jarvis.common.ai.Brain;
import com.jarvis.common.ai.BrainType;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultToolCallingRuntimeTest {

    @Test
    void doesNotExposeMainToolRequestEnvelopeAsFinalAnswer() {
        AtomicReference<ToolRequest> executed = new AtomicReference<>();
        AtomicInteger executions = new AtomicInteger();
        DefaultToolCallingRuntime runtime = new DefaultToolCallingRuntime(
                List.of(new StubProvider("""
                        {
                          "type": "TOOL_REQUEST",
                          "goal": "Retrieve the current exchange rate for USD to PLN from a reliable financial source.",
                          "reason": "The requested currency conversion rate is dynamic and requires up-to-date external data.",
                          "context": {"importantEntities": []}
                        }
                        """)),
                new StubToolManager(executed, executions),
                webRegistry(),
                query -> ToolIntent.SEARCH_WEB,
                new ToolRuntimeProperties(true, 2, 2, 1, 30),
                new NoopCognitiveEventBus(),
                new ToolRuntimeDebugService(),
                new ObjectMapper()
        );

        ToolCallingResult result = runtime.execute(new ToolCallingRequest(
                "request-1",
                "conversation-1",
                "sprawdz kurs Dolara na pln",
                "Retrieve current USD to PLN exchange rate",
                "Requires live market data",
                "Base prompt",
                new Brain(BrainType.FAST, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.LOW),
                KnowledgeMode.FAST
        ));

        assertThat(result.handled()).isTrue();
        assertThat(result.finalAnswer()).doesNotContain("\"type\"");
        assertThat(executed.get()).isNotNull();
        assertThat(executed.get().toolName()).isEqualTo("web");
        assertThat(executed.get().operation()).isEqualTo("SEARCH_WEB");
        assertThat(executed.get().arguments()).containsEntry("query", "sprawdz kurs Dolara na pln");
        assertThat(executions).hasValue(1);
    }

    private ToolRegistry webRegistry() {
        ToolDefinition definition = new ToolDefinition("web", "Web search", List.of(
                new ToolOperationDefinition("SEARCH_WEB", "Search web", List.of(
                        new ToolArgumentDefinition("query", "string", true, "Search query"),
                        new ToolArgumentDefinition("maxResults", "integer", false, "Maximum results")
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

    private static final class StubProvider implements com.jarvis.common.ai.AIProvider {

        private final String response;

        private StubProvider(String response) {
            this.response = response;
        }

        @Override
        public String provider() {
            return "stub";
        }

        @Override
        public ChatResponse chat(Brain brain, String prompt) {
            return new ChatResponse(response);
        }

        @Override
        public ChatResponse chat(Brain brain, String prompt, AIJobType jobType) {
            return new ChatResponse(response);
        }

        @Override
        public void stream(String conversationId, Brain brain, String prompt, ChatEventSink eventSink) {
        }
    }

    private static final class StubToolManager implements ToolManager {

        private final AtomicReference<ToolRequest> executed;
        private final AtomicInteger executions;

        private StubToolManager(AtomicReference<ToolRequest> executed, AtomicInteger executions) {
            this.executed = executed;
            this.executions = executions;
        }

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
            executed.set(request);
            executions.incrementAndGet();
            return new ToolResult(true, "web", "SEARCH_WEB", request.requestId(), request.conversationId(), false,
                    List.of("web:search"), "Found USD PLN rate", Map.of("query", request.arguments().get("query")),
                    "", "", false, "");
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
