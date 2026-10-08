package com.jarvis.tools.planner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.common.ai.BrainType;
import com.jarvis.common.event.CognitiveEventBus;
import com.jarvis.common.event.CognitiveEventType;
import com.jarvis.tools.ToolRequest;
import com.jarvis.tools.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class PlanToolTest {

    @TempDir
    Path tempDir;

    private final List<CognitiveEventType> published = new ArrayList<>();

    private final CognitiveEventBus bus = new CognitiveEventBus() {
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
            published.add(event);
        }
    };

    @Test
    void createUpdateAndAutoAdvanceAcrossRestart() {
        TaskPlanService service = new TaskPlanService(new ObjectMapper(), tempDir.toString());
        PlanTool tool = new PlanTool(service, bus);

        ToolResult created = tool.execute(request("CREATE", Map.of("goal", "Fix build",
                "steps", "1. Run build\n2. Fix compile errors\n- Run tests")));
        assertThat(created.success()).isTrue();
        assertThat(created.data().get("total")).isEqualTo(3);
        assertThat(created.message()).contains("[>] 1. Run build").contains("Now execute step 1");

        ToolResult updated = tool.execute(request("UPDATE_STEP", Map.of("step", 1, "status", "zrobione", "note", "2 errors")));
        assertThat(updated.message()).contains("[x] 1. Run build — 2 errors").contains("[>] 2. Fix compile errors");
        assertThat(published).containsExactly(CognitiveEventType.PLAN_UPDATED, CognitiveEventType.PLAN_UPDATED);

        // A fresh service instance (Core restart) still sees the unfinished plan on disk.
        TaskPlanService restarted = new TaskPlanService(new ObjectMapper(), tempDir.toString());
        assertThat(restarted.findUnfinished("conv")).isPresent();
        assertThat(restarted.findUnfinished("conv").get().nextStep().orElseThrow().number()).isEqualTo(2);

        tool.execute(request("UPDATE_STEP", Map.of("step", "2", "status", "done")));
        ToolResult last = tool.execute(request("UPDATE_STEP", Map.of("step", 3, "status", "blocked", "note", "no test runner")));
        assertThat(last.data().get("finished")).isEqualTo(true);
        assertThat(last.message()).contains("give the final answer");
        assertThat(service.findUnfinished("conv")).isEmpty();
    }

    @Test
    void addStepsAndJsonArrayInput() {
        TaskPlanService service = new TaskPlanService(new ObjectMapper(), "");
        PlanTool tool = new PlanTool(service, bus);
        tool.execute(request("CREATE", Map.of("goal", "g", "steps", List.of("a"))));
        tool.execute(request("UPDATE_STEP", Map.of("step", 1, "status", "done")));
        ToolResult added = tool.execute(request("ADD_STEPS", Map.of("steps", List.of("b", "c"))));
        assertThat(added.message()).contains("[>] 2. b").contains("[ ] 3. c");
    }

    @Test
    void invalidInputIsAToolFailureNotAnException() {
        PlanTool tool = new PlanTool(new TaskPlanService(new ObjectMapper(), ""), bus);
        assertThat(tool.execute(request("UPDATE_STEP", Map.of("step", 1, "status", "done"))).success()).isFalse();
        assertThat(tool.execute(request("CREATE", Map.of("goal", "g", "steps", ""))).success()).isFalse();
        tool.execute(request("CREATE", Map.of("goal", "g", "steps", "a")));
        assertThat(tool.execute(request("UPDATE_STEP", Map.of("step", 1, "status", "whatever"))).success()).isFalse();
    }

    private ToolRequest request(String operation, Map<String, Object> arguments) {
        return new ToolRequest("plan", operation, "conv", "req", "", "", arguments);
    }
}
