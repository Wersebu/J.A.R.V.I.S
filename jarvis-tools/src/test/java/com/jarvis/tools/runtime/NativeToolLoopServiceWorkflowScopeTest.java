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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reported conversation: the calendar workflow was preloaded for a store audit follow-up, so only calendar tools
 * were offered; the model called "storeDataset.CREATE_DATASET" (the spelling of the workflow documents), got "invalid
 * tool", and then told the user it was "processing the data in the background".
 */
class NativeToolLoopServiceWorkflowScopeTest {

    private final List<List<String>> offeredPerTurn = new CopyOnWriteArrayList<>();
    private final List<List<ModelMessage>> messagesPerTurn = new CopyOnWriteArrayList<>();
    private final List<ToolRequest> executed = new CopyOnWriteArrayList<>();
    private volatile boolean addFails;

    @Test
    void aRealToolOutsideTheWorkflowScopeRunsAndItsFamilyIsOfferedFromThenOn() {
        Deque<ModelResponse> turns = new ArrayDeque<>();
        turns.add(toolCall("notes.ADD", Map.of("text", "Baniocha 05-532")));
        turns.add(toolCall("notes__list", Map.of()));
        turns.add(answer("Zapisałem notatkę o Baniochy."));

        ToolCallingResult result = run(turns, Map.of("workflowTools", List.of("calendar__events")));

        assertThat(result.finalAnswer()).contains("Zapisałem notatkę");
        // Dotted name accepted and executed instead of "Invalid native tool name".
        assertThat(executed).extracting(ToolRequest::operation).containsExactly("ADD", "LIST");
        assertThat(offeredPerTurn.getFirst()).contains("calendar__events").doesNotContain("notes__add", "notes__list");
        assertThat(offeredPerTurn.get(1)).contains("calendar__events", "notes__add", "notes__list");
    }

    @Test
    void aPromiseOfBackgroundWorkIsSentBackOnceInsteadOfReachingTheUser() {
        Deque<ModelResponse> turns = new ArrayDeque<>();
        turns.add(toolCall("notes__list", Map.of()));
        turns.add(answer("Przechodzę do geolokalizacji. W tej chwili przetwarzam dane geograficzne, odezwę się, gdy skończę."));
        turns.add(answer("Nie mam narzędzia do geolokalizacji w tej rozmowie - napisz proszę ponownie prośbę o grafik."));

        ToolCallingResult result = run(turns, Map.of());

        // The promise never reaches the user (later answers may still be judged by the goal gate, as before).
        assertThat(result.finalAnswer()).doesNotContain("przetwarzam", "odezwę się");
        assertThat(messagesPerTurn).hasSizeGreaterThanOrEqualTo(3);
        assertThat(messagesPerTurn.get(2)).anySatisfy(message -> assertThat(message.content()).contains("nothing runs after you answer"));
    }

    @Test
    void claimingASaveThatFailedIsSentBackOnce() {
        addFails = true;
        Deque<ModelResponse> turns = new ArrayDeque<>();
        turns.add(toolCall("notes__add", Map.of("text", "preferencje: wtorki i środy")));
        turns.add(answer("Zapisałem Twoje preferencje na listopad."));
        turns.add(answer("Nie udało się zapisać preferencji: brak zbioru danych. Najpierw muszę zapisać listę sklepów."));

        ToolCallingResult result = run(turns, Map.of());

        assertThat(result.finalAnswer()).doesNotContain("Zapisałem");
        assertThat(messagesPerTurn.get(2)).anySatisfy(message -> assertThat(message.content()).contains("Your last tool call FAILED", "notes.ADD"));
        assertThat(NativeToolLoopService.claimsSuccess("Zapisuję Twoje preferencje.")).isTrue();
        assertThat(NativeToolLoopService.acknowledgesProblem("Nie udało się zapisać.")).isTrue();
    }

    @Test
    void ordinaryPoliteRepliesAreNotMistakenForPromises() {
        assertThat(NativeToolLoopService.promisesBackgroundWork("W tej chwili przetwarzam dane geograficzne.")).isTrue();
        assertThat(NativeToolLoopService.promisesBackgroundWork("Pracuję nad tym w tle i wrócę z wynikiem.")).isTrue();
        assertThat(NativeToolLoopService.promisesBackgroundWork("I'm processing it now, I will get back to you.")).isTrue();
        assertThat(NativeToolLoopService.promisesBackgroundWork("Dam znać, jeśli coś się zmieni - plan jest gotowy.")).isFalse();
        assertThat(NativeToolLoopService.promisesBackgroundWork("Przetworzyłem 18 sklepów, oto grafik.")).isFalse();
        assertThat(NativeToolLoopService.promisesBackgroundWork("Tło zdjęcia jest białe.")).isFalse();
    }

    private ToolCallingResult run(Deque<ModelResponse> turns, Map<String, Object> context) {
        NativeToolLoopService service = new NativeToolLoopService(List.of(new ScriptedProvider(turns)), new NotesToolManager(),
                query -> ToolIntent.UPDATE_DOCUMENT, new ToolRuntimeProperties(true, 6, 6, 1, 30, "native"), new QuietBus(),
                new ToolRuntimeDebugService(), new ObjectMapper(), new NativeToolSchemaMapper(registry()),
                new com.jarvis.tools.dataset.StoreAuditDatasetService(new QuietBus()));
        return service.execute(new ToolCallingRequest("request-1", "conversation-1", "zapisz notatkę o Baniochy",
                "Save a note.", "The user asked to save a note.", context, "Base prompt",
                new Brain(BrainType.FAST, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.LOW), KnowledgeMode.FAST, List.of(), ""));
    }

    private static ModelResponse toolCall(String name, Map<String, Object> arguments) {
        return new ModelResponse("", "", List.of(new ModelToolCall("call-" + name, name, arguments)), "tool_calls", new ModelUsage(0, 0, 0));
    }

    private static ModelResponse answer(String text) {
        return new ModelResponse(text, "", List.of(), "stop", new ModelUsage(0, 0, 0));
    }

    private static ToolRegistry registry() {
        ToolDefinition calendar = new ToolDefinition("calendar", "Calendar", List.of(
                new ToolOperationDefinition("EVENTS", "Events.", List.of(), false, ToolSafetyLevel.READ)));
        ToolDefinition notes = new ToolDefinition("notes", "Notes", List.of(
                new ToolOperationDefinition("ADD", "Add a note.", List.of(new ToolArgumentDefinition("text", "string", true, "Note")),
                        true, ToolSafetyLevel.WRITE),
                new ToolOperationDefinition("LIST", "List notes.", List.of(), false, ToolSafetyLevel.READ)));
        ToolDefinition knowledge = new ToolDefinition("knowledge", "Knowledge", List.of(
                new ToolOperationDefinition("SEARCH_CONTENT", "Search.", List.of(new ToolArgumentDefinition("query", "string", true, "Query")),
                        false, ToolSafetyLevel.READ)));
        return new ToolRegistry() {
            @Override
            public List<ToolDefinition> definitions() {
                return List.of(calendar, notes, knowledge);
            }

            @Override
            public String promptSection() {
                return "Tools: calendar, notes, knowledge";
            }
        };
    }

    private final class ScriptedProvider implements AIProvider {
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
            offeredPerTurn.add(tools.stream().map(NativeToolDefinition::name).toList());
            messagesPerTurn.add(List.copyOf(messages));
            return turns.isEmpty() ? answer("") : turns.poll();
        }
    }

    private final class NotesToolManager implements ToolManager {
        @Override
        public List<JarvisTool> listTools() {
            return List.of();
        }

        @Override
        public Optional<JarvisTool> findTool(String name) {
            return Optional.of(new JarvisTool() {
                @Override
                public String getName() {
                    return name;
                }

                @Override
                public String getDescription() {
                    return name;
                }

                @Override
                public ToolResult execute(ToolRequest request) {
                    return NotesToolManager.this.execute(request);
                }
            });
        }

        @Override
        public ToolResult execute(ToolRequest request) {
            executed.add(request);
            if (addFails && "ADD".equals(request.operation())) {
                return new ToolResult(false, request.toolName(), request.operation(), request.requestId(), request.conversationId(),
                        false, List.of(), "No dataset", Map.of(), "STORE_DATASET_NOT_FOUND", "No dataset in this conversation", false, "");
            }
            return new ToolResult(true, request.toolName(), request.operation(), request.requestId(), request.conversationId(),
                    "ADD".equals(request.operation()), List.of(), "Notes " + request.operation() + " done",
                    Map.of("notes", new ArrayList<>(List.of("Baniocha 05-532"))), "", "", false, "");
        }
    }

    private static final class QuietBus implements CognitiveEventBus {
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
