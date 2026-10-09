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

    @Test
    void finalAnswerStepIsCompletedByTheAnswerItself() {
        CognitiveEventBus bus = new NoopBus();
        TaskPlanService plans = new TaskPlanService(new ObjectMapper(), "");
        PlanTool planTool = new PlanTool(plans, bus);
        Deque<ModelResponse> turns = new ArrayDeque<>();
        turns.add(call("plan__create", Map.of("goal", "version", "steps", List.of("List files", "Read README.md", "Podaj odpowiedź użytkownikowi"))));
        turns.add(call("pc__read", Map.of("path", "README.md")));
        turns.add(call("plan__update_step", Map.of("step", 1, "status", "done")));
        turns.add(call("plan__update_step", Map.of("step", 2, "status", "done")));
        turns.add(text("Najnowsza wersja aplikacji to 2.0.28 (z README.md)."));
        RecordingProvider provider = new RecordingProvider(turns);
        NativeToolLoopService service = service(provider, planTool, plans, bus);

        ToolCallingResult result = service.execute(request("conv-final"));

        assertThat(result.finalAnswer()).contains("2.0.28");
        assertThat(provider.calls).isEqualTo(5);
        assertThat(plans.findUnfinished("conv-final")).isEmpty();
    }

    @Test
    void aRealAnswerIsNeverReplacedByTheGenericBlockedMessage() {
        CognitiveEventBus bus = new NoopBus();
        TaskPlanService plans = new TaskPlanService(new ObjectMapper(), "");
        PlanTool planTool = new PlanTool(plans, bus);
        Deque<ModelResponse> turns = new ArrayDeque<>();
        turns.add(call("plan__create", Map.of("goal", "version", "steps", List.of("a", "b", "c"))));
        turns.add(call("pc__read", Map.of("path", "README.md")));
        turns.add(text("Najnowsza wersja aplikacji to 2.0.28 (z README.md)."));
        // Sent back by the plan gate, the model believes it already answered and outputs nothing.
        turns.add(text(""));
        turns.add(text(""));
        turns.add(text(""));
        turns.add(text(""));
        RecordingProvider provider = new RecordingProvider(turns);
        NativeToolLoopService service = service(provider, planTool, plans, bus);

        ToolCallingResult result = service.execute(request("conv-held"));

        assertThat(result.finalAnswer()).contains("2.0.28").doesNotContain("Nie mogę rzetelnie");
        assertThat(provider.sawSystemNote("the user has NOT seen your message above")).isTrue();
    }

    @Test
    void readingManyDifferentFilesAndRereadingAfterAnEditIsNotBlockedAsNoProgress() {
        CognitiveEventBus bus = new NoopBus();
        TaskPlanService plans = new TaskPlanService(new ObjectMapper(), "");
        PlanTool planTool = new PlanTool(plans, bus);
        Deque<ModelResponse> turns = new ArrayDeque<>();
        for (int i = 1; i <= 8; i++) {
            turns.add(call("pc__read", Map.of("path", "lib/file" + i + ".js")));
        }
        turns.add(call("pc__edit", Map.of("path", "lib/file1.js")));
        turns.add(call("pc__read", Map.of("path", "lib/file1.js")));   // verification re-read
        turns.add(text("Przeczytałem 8 plików i poprawiłem file1.js - wersja 2.0.28 bez zmian."));
        RecordingProvider provider = new RecordingProvider(turns);
        NativeToolLoopService service = service(provider, planTool, plans, bus);

        ToolCallingResult result = service.execute(new ToolCallingRequest("r", "conv-many", "przeanalizuj pliki", "audit", "",
                Map.of("workingDirectory", "D:\\JarvisBot"), "Base",
                new Brain(BrainType.FAST, "stub", "m", "stub", "", 0L, ReasoningLevel.LOW), KnowledgeMode.FAST, List.of(), ""));

        assertThat(result.finalAnswer()).contains("Przeczytałem 8 plików");
        assertThat(result.steps()).noneMatch(step -> "NO_PROGRESS_BLOCKED".equals(step.action())
                || "DUPLICATE_TOOL_CALL".equals(step.action()));
    }

    @Test
    void screenshotsAreShownToAVisionModelAsImagesNotAsTextAndOnlyTheNewestTwoAreKept() {
        CognitiveEventBus bus = new NoopBus();
        TaskPlanService plans = new TaskPlanService(new ObjectMapper(), "");
        PlanTool planTool = new PlanTool(plans, bus);
        Deque<ModelResponse> turns = new ArrayDeque<>();
        turns.add(call("pc__read", Map.of("path", "index.html")));
        turns.add(call("pc__screenshot", Map.of("path", "index.html")));
        turns.add(call("pc__screenshot", Map.of("path", "index.html", "device", "mobile")));
        turns.add(call("pc__screenshot", Map.of("path", "index.html", "width", 1600)));
        turns.add(text("Strona sprawdzona na 3 zrzutach, poprawki naniesione - wersja 2.0.28."));
        RecordingProvider provider = new RecordingProvider(turns);
        NativeToolLoopService service = service(provider, planTool, plans, bus);
        service.setActiveModelService(new com.jarvis.common.model.ActiveModelService() {
            @Override public String activeModel() { return "gemma4"; }
            @Override public java.util.Set<com.jarvis.common.model.ModelCapability> activeModelCapabilities() {
                return java.util.Set.of(com.jarvis.common.model.ModelCapability.VISION);
            }
            @Override public com.jarvis.common.model.ModelCatalog catalog() { return null; }
            @Override public com.jarvis.common.model.ModelSwitchResult switchTo(String requestedModel) { return null; }
        });

        ToolCallingResult result = service.execute(request("conv-shot"));

        assertThat(result.finalAnswer()).contains("3 zrzutach");
        List<ModelMessage> last = provider.lastMessages;
        long withImages = last.stream().filter(message -> !message.images().isEmpty()).count();
        assertThat(withImages).isEqualTo(2);
        assertThat(last.stream().filter(message -> "tool".equals(message.role())).map(ModelMessage::content))
                .allMatch(content -> !content.contains("iVBORw0KGgo"))
                .anyMatch(content -> content.contains("attached in the next message"));
        assertThat(provider.sawSystemNote("Quality loop")).isTrue();
    }

    @Test
    void aToolRequestWrittenAsTextAmidCodeWithBracesIsSentBackForARealToolCall() {
        CognitiveEventBus bus = new NoopBus();
        TaskPlanService plans = new TaskPlanService(new ObjectMapper(), "");
        PlanTool planTool = new PlanTool(plans, bus);
        Deque<ModelResponse> turns = new ArrayDeque<>();
        turns.add(call("pc__read", Map.of("path", "index.html")));
        turns.add(text("""
                I will add the review card. Current CSS: .review-card { padding: 30px; } and the card:
                ```html
                <div class="review-card glass">...</div>
                ```
                I will formulate a TOOL_REQUEST for pc:WRITE.
                {"type": "TOOL_REQUEST", "goal": "Update index.html with the new review", "reason": "inject it", "context": {"importantEntities": ["C:\\\\x\\\\index.html"]}}
                """));
        turns.add(call("pc__edit", Map.of("path", "index.html")));
        turns.add(text("Dodałem opinię Andrzeja Kowalskiego do sekcji opinii (wersja 2.0.28)."));
        RecordingProvider provider = new RecordingProvider(turns);
        NativeToolLoopService service = service(provider, planTool, plans, bus);

        ToolCallingResult result = service.execute(request("conv-text-request"));

        assertThat(result.finalAnswer()).contains("Dodałem opinię");
        assertThat(provider.sawSystemNote("You described a tool request")).isTrue();
        assertThat(result.steps()).anyMatch(step -> "EDIT".equalsIgnoreCase(step.operation()));
    }

    @Test
    void messagesSentWhileWorkingReachTheModelAsUserTurns() {
        CognitiveEventBus bus = new NoopBus();
        TaskPlanService plans = new TaskPlanService(new ObjectMapper(), "");
        PlanTool planTool = new PlanTool(plans, bus);
        Deque<ModelResponse> turns = new ArrayDeque<>();
        turns.add(call("pc__read", Map.of("path", "index.html")));
        turns.add(text("Gotowe - dodałem też stopkę (wersja 2.0.28)."));
        RecordingProvider provider = new RecordingProvider(turns);
        NativeToolLoopService service = service(provider, planTool, plans, bus);
        Object control = com.jarvis.common.run.ChatRunControl.open("conv-steer", null);
        try {
            assertThat(com.jarvis.common.run.ChatRunControl.post("conv-steer", "dodaj jeszcze stopkę")).isTrue();

            ToolCallingResult result = service.execute(request("conv-steer"));

            assertThat(result.finalAnswer()).contains("stopkę");
            assertThat(provider.lastMessages).anyMatch(message -> "user".equals(message.role())
                    && message.content().contains("dodaj jeszcze stopkę") && message.content().contains("while you were working"));
            assertThat(com.jarvis.common.run.ChatRunControl.hasPending("conv-steer")).isFalse();
        } finally {
            com.jarvis.common.run.ChatRunControl.close("conv-steer", control);
        }
        assertThat(com.jarvis.common.run.ChatRunControl.post("conv-steer", "za późno")).isFalse();
    }

    @Test
    void stopEndsTheLoopAtTheNextStep() {
        CognitiveEventBus bus = new NoopBus();
        TaskPlanService plans = new TaskPlanService(new ObjectMapper(), "");
        PlanTool planTool = new PlanTool(plans, bus);
        Deque<ModelResponse> turns = new ArrayDeque<>();
        turns.add(call("pc__read", Map.of("path", "index.html")));
        turns.add(text("nie powinno do tego dojść"));
        RecordingProvider provider = new RecordingProvider(turns);
        NativeToolLoopService service = service(provider, planTool, plans, bus);
        Object control = com.jarvis.common.run.ChatRunControl.open("conv-stop", null);
        try {
            assertThat(com.jarvis.common.run.ChatRunControl.cancel("conv-stop")).isTrue();
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.execute(request("conv-stop")))
                    .isInstanceOf(com.jarvis.common.run.ChatRunCancelledException.class);
            assertThat(provider.calls).isZero();
        } finally {
            com.jarvis.common.run.ChatRunControl.close("conv-stop", control);
        }
    }

    @Test
    void agentWorkIsNeverCutOffByTheClockAndGetsMoreTurnsWhileMakingProgress() {
        CognitiveEventBus bus = new NoopBus();
        TaskPlanService plans = new TaskPlanService(new ObjectMapper(), "");
        PlanTool planTool = new PlanTool(plans, bus);
        Deque<ModelResponse> turns = new ArrayDeque<>();
        for (int i = 1; i <= 9; i++) {
            turns.add(call("pc__read", Map.of("path", "src/file" + i + ".js")));
        }
        turns.add(text("Przeczytałem 9 plików, wszystko gotowe (wersja 2.0.28)."));
        RecordingProvider provider = new RecordingProvider(turns);
        provider.delayMillis = 250; // 10 turns take ~2.5 s, the configured wall clock allows 1 s
        // timeoutSeconds=1, maxCallsAgent=6: both would have stopped this loop before.
        NativeToolLoopService service = service(provider, planTool, plans, bus,
                new ToolRuntimeProperties(true, 3, 3, 1, 1, "native", 5, 20, 2, 3, 6));

        ToolCallingResult result = service.execute(request("conv-long"));

        assertThat(result.finalAnswer()).contains("Przeczytałem 9 plików");
        assertThat(result.steps()).filteredOn(step -> "TOOL_CALL".equals(step.action())).hasSize(9);
    }

    private NativeToolLoopService service(RecordingProvider provider, PlanTool planTool, TaskPlanService plans, CognitiveEventBus bus) {
        return service(provider, planTool, plans, bus, new ToolRuntimeProperties(true, 3, 3, 1, 0, "native", 5, 20, 2, 3, 30));
    }

    private NativeToolLoopService service(RecordingProvider provider, PlanTool planTool, TaskPlanService plans, CognitiveEventBus bus,
                                          ToolRuntimeProperties properties) {
        JarvisTool pc = fakePcReadTool();
        ToolManager manager = new ToolManager() {
            @Override public List<JarvisTool> listTools() { return List.of(planTool, pc); }
            @Override public Optional<JarvisTool> findTool(String name) {
                return "pc".equalsIgnoreCase(name) ? Optional.of(pc) : "plan".equalsIgnoreCase(name) ? Optional.of(planTool) : Optional.empty();
            }
            @Override public ToolResult execute(ToolRequest request) { return findTool(request.toolName()).orElseThrow().execute(request); }
        };
        NativeToolLoopService service = new NativeToolLoopService(List.of(provider), manager,
                query -> ToolIntent.NO_TOOL, properties, bus,
                new ToolRuntimeDebugService(), new ObjectMapper(), new NativeToolSchemaMapper(registryWithPc(planTool)),
                new com.jarvis.tools.dataset.StoreAuditDatasetService(bus));
        service.setTaskPlanService(plans);
        return service;
    }

    private static JarvisTool fakePcReadTool() {
        return new JarvisTool() {
            @Override public String getName() { return "pc"; }
            @Override public String getDescription() { return "pc"; }
            @Override public ToolResult execute(ToolRequest request) {
                if ("SCREENSHOT".equalsIgnoreCase(request.operation())) {
                    return new ToolResult(true, "pc", "SCREENSHOT", request.requestId(), request.conversationId(), false, List.of(),
                            "PC SCREENSHOT finished", Map.of("target", "file:///index.html", "viewport", "1366x900",
                            "_imageBase64", "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="),
                            "", "", false, "");
                }
                return new ToolResult(true, "pc", "READ", request.requestId(), request.conversationId(), false, List.of(),
                        "PC READ finished", Map.of("path", String.valueOf(request.arguments().getOrDefault("path", "")),
                        "content", "   1\tWersja aplikacji: 2.0.28"), "", "", false, "");
            }
        };
    }

    private static ToolRegistry registryWithPc(PlanTool tool) {
        ToolDefinition pc = new ToolDefinition("pc", "pc", List.of(
                new com.jarvis.tools.schema.ToolOperationDefinition("READ", "read",
                        List.of(new com.jarvis.tools.schema.ToolArgumentDefinition("path", "string", true, "path")), false,
                        com.jarvis.tools.schema.ToolSafetyLevel.READ),
                new com.jarvis.tools.schema.ToolOperationDefinition("SCREENSHOT", "screenshot",
                        List.of(new com.jarvis.tools.schema.ToolArgumentDefinition("path", "string", false, "path"),
                                new com.jarvis.tools.schema.ToolArgumentDefinition("device", "string", false, "device"),
                                new com.jarvis.tools.schema.ToolArgumentDefinition("width", "integer", false, "width")), false,
                        com.jarvis.tools.schema.ToolSafetyLevel.READ),
                new com.jarvis.tools.schema.ToolOperationDefinition("EDIT", "edit",
                        List.of(new com.jarvis.tools.schema.ToolArgumentDefinition("path", "string", true, "path")), true,
                        com.jarvis.tools.schema.ToolSafetyLevel.WRITE)));
        return new ToolRegistry() {
            @Override public List<ToolDefinition> definitions() { return List.of(tool.definition(), pc); }
            @Override public String promptSection() { return ""; }
        };
    }

    private static ToolCallingRequest request(String conversationId) {
        return new ToolCallingRequest("r", conversationId, "podaj wersje", "find version", "", "Base",
                new Brain(BrainType.FAST, "stub", "m", "stub", "", 0L, ReasoningLevel.LOW), KnowledgeMode.FAST);
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
        private List<ModelMessage> lastMessages = List.of();
        private int calls;
        private long delayMillis;

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
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
            lastMessages = List.copyOf(messages);
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
