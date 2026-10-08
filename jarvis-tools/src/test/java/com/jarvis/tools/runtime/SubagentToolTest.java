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
import com.jarvis.tools.planner.PlanTool;
import com.jarvis.tools.planner.TaskPlanService;
import com.jarvis.tools.schema.ToolDefinition;
import com.jarvis.tools.schema.ToolRegistry;
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
 * The model must not give its final answer while its own plan still has unfinished steps; Core sends
 * it back to work (bounded), and a plan raises the turn budget to the agent budget.
 */
class SubagentToolTest {

    @Test
    void helperAgentRunsInItsOwnLoopAndReturnsOnlyItsReport() {
        CognitiveEventBus bus = new NoopBus();
        java.util.concurrent.atomic.AtomicReference<NativeToolLoopService> holder = new java.util.concurrent.atomic.AtomicReference<>();
        org.springframework.beans.factory.support.StaticListableBeanFactory beans = new org.springframework.beans.factory.support.StaticListableBeanFactory();
        SubagentTool agentTool = new SubagentTool(beans.getBeanProvider(NativeToolLoopService.class));
        JarvisTool echo = new JarvisTool() {
            @Override public String getName() { return "plan"; }
            @Override public String getDescription() { return "x"; }
            @Override public ToolResult execute(ToolRequest request) {
                return new ToolResult(true, "plan", "GET", request.requestId(), request.conversationId(), false, List.of(),
                        "big raw data seen by helper", Map.of("conversation", request.conversationId(), "raw", "X".repeat(5000)), "", "", false, "");
            }
        };
        PlanTool planDefinitions = new PlanTool(new TaskPlanService(new ObjectMapper(), ""), bus);
        Deque<ModelResponse> turns = new ArrayDeque<>();
        turns.add(call("agent__run", Map.of("task", "find the login code")));   // parent turn 1
        turns.add(call("plan__get", Map.of()));                                   // helper turn 1
        turns.add(text("REPORT: login is in Auth.java:42"));                     // helper final
        turns.add(text("Parent done using report"));                             // parent final
        RecordingProvider provider = new RecordingProvider(turns);
        ToolManager manager = new ToolManager() {
            @Override public List<JarvisTool> listTools() { return List.of(agentTool, echo); }
            @Override public Optional<JarvisTool> findTool(String name) {
                return "agent".equalsIgnoreCase(name) ? Optional.of(agentTool) : "plan".equalsIgnoreCase(name) ? Optional.of(echo) : Optional.empty();
            }
            @Override public ToolResult execute(ToolRequest request) { return findTool(request.toolName()).orElseThrow().execute(request); }
        };
        NativeToolLoopService service = new NativeToolLoopService(List.of(provider), manager, query -> ToolIntent.NO_TOOL,
                new ToolRuntimeProperties(true, 6, 6, 1, 0, "native", 5, 20, 2, 3, 30), bus, new ToolRuntimeDebugService(),
                new ObjectMapper(), new NativeToolSchemaMapper(registry(planDefinitions, agentTool)),
                new com.jarvis.tools.dataset.StoreAuditDatasetService(bus));
        beans.addBean("loop", service);

        ToolCallingResult result = service.execute(new ToolCallingRequest("r", "conv", "where is login?", "find login", "",
                "Base", new Brain(BrainType.FAST, "stub", "m", "stub", "", 0L, ReasoningLevel.LOW), KnowledgeMode.FAST));

        assertThat(result.finalAnswer()).contains("Parent done");
        String parentToolMessage = provider.toolMessages.stream().filter(m -> m.contains("REPORT: login is in Auth.java:42")).findFirst().orElse("");
        assertThat(parentToolMessage).isNotEmpty().doesNotContain("XXXXXXXXXX");
        assertThat(provider.toolMessages).anyMatch(m -> m.contains("conv#agent"));
        assertThat(NativeToolLoopService.currentRequest()).isNull();
    }

    private static ModelResponse call(String name, Map<String, Object> args) {
        return new ModelResponse("", "", List.of(new ModelToolCall("c-" + name, name, args)), "tool_calls", new ModelUsage(0, 0, 0));
    }

    private static ModelResponse text(String content) {
        return new ModelResponse(content, "", List.of(), "stop", new ModelUsage(0, 0, 0));
    }

    private static ToolRegistry registry(PlanTool tool, SubagentTool agent) {
        return new ToolRegistry() {
            @Override
            public List<ToolDefinition> definitions() {
                return List.of(tool.definition(), agent.definition());
            }

            @Override
            public String promptSection() {
                return "";
            }
        };
    }

    private static final class RecordingProvider implements AIProvider {
        private final Deque<ModelResponse> turns;
        private final List<String> systemNotes = new ArrayList<>();
        private final List<String> toolMessages = new ArrayList<>();
        private int calls;

        private RecordingProvider(Deque<ModelResponse> turns) {
            this.turns = turns;
        }

        boolean sawSystemNote(String fragment) {
            return systemNotes.stream().anyMatch(note -> note.contains(fragment));
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
            messages.stream().filter(message -> "system".equalsIgnoreCase(String.valueOf(message.role())))
                    .forEach(message -> systemNotes.add(String.valueOf(message.content())));
            messages.stream().filter(message -> "tool".equalsIgnoreCase(String.valueOf(message.role())))
                    .forEach(message -> toolMessages.add(String.valueOf(message.content())));
            return turns.isEmpty() ? text("") : turns.poll();
        }
    }

    private static final class SingleToolManager implements ToolManager {
        private final JarvisTool tool;

        private SingleToolManager(JarvisTool tool) {
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

    private static final class NoopBus implements CognitiveEventBus {
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
