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
class NativeToolLoopServicePlanGateTest {

    @Test
    void finalAnswerWithUnfinishedPlanIsSentBackToWork() {
        CognitiveEventBus bus = new NoopBus();
        TaskPlanService plans = new TaskPlanService(new ObjectMapper(), "");
        PlanTool planTool = new PlanTool(plans, bus);

        Deque<ModelResponse> turns = new ArrayDeque<>();
        turns.add(call("plan__create", Map.of("goal", "Two things", "steps", List.of("first", "second"))));
        turns.add(call("plan__update_step", Map.of("step", 1, "status", "done")));
        turns.add(text("Gotowe!"));                       // premature: step 2 still open
        turns.add(call("plan__update_step", Map.of("step", 2, "status", "done", "note", "ok")));
        turns.add(text("Zrobione oba kroki."));
        RecordingProvider provider = new RecordingProvider(turns);

        NativeToolLoopService service = new NativeToolLoopService(
                List.of(provider),
                new SingleToolManager(planTool),
                query -> ToolIntent.NO_TOOL,
                new ToolRuntimeProperties(true, 3, 3, 1, 0, "native", 5, 20, 2, 3, 30),
                bus,
                new ToolRuntimeDebugService(),
                new ObjectMapper(),
                new NativeToolSchemaMapper(registry(planTool)),
                new com.jarvis.tools.dataset.StoreAuditDatasetService(bus)
        );
        service.setTaskPlanService(plans);

        ToolCallingResult result = service.execute(new ToolCallingRequest("r", "conv-plan", "zrob dwie rzeczy",
                "do two things", "", "Base", new Brain(BrainType.FAST, "stub", "m", "stub", "", 0L, ReasoningLevel.LOW),
                KnowledgeMode.FAST));

        assertThat(result.finalAnswer()).contains("Zrobione oba kroki");
        assertThat(plans.findUnfinished("conv-plan")).isEmpty();
        // Budget 3 would have ended before the 5th turn; the plan raised it to the agent budget.
        assertThat(provider.calls).isEqualTo(5);
        assertThat(provider.sawSystemNote("Your plan still has unfinished steps")).isTrue();
        assertThat(provider.sawSystemNote("Working style (autonomous agent)")).isTrue();
    }

    private static ModelResponse call(String name, Map<String, Object> args) {
        return new ModelResponse("", "", List.of(new ModelToolCall("c-" + name, name, args)), "tool_calls", new ModelUsage(0, 0, 0));
    }

    private static ModelResponse text(String content) {
        return new ModelResponse(content, "", List.of(), "stop", new ModelUsage(0, 0, 0));
    }

    private static ToolRegistry registry(PlanTool tool) {
        return new ToolRegistry() {
            @Override
            public List<ToolDefinition> definitions() {
                return List.of(tool.definition());
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
