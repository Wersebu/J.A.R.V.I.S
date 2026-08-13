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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for duplicate tool-call detection in {@link NativeToolLoopService}.
 *
 * <p>Duplicate identity must be (concrete tool, concrete operation, canonically normalized
 * arguments). Different operations on the same topic (SEARCH, then LIST, then READ) are never
 * duplicates of each other, even though they serve the same overall goal. Only a call with the
 * exact same tool + operation + arguments repeated without new information must be blocked.
 */
class NativeToolLoopServiceDuplicateDetectionTest {

    private static final String GPU_DOCUMENT_PATH = "hardware/graphics_card.txt";
    private static final String GPU_CONTENT = "RTX 4060 Ti 16 GB";

    @Test
    void searchListReadWebSequenceIsNeverBlockedAsDuplicate() {
        Deque<ModelResponse> turns = new ArrayDeque<>();
        turns.add(toolCallTurn("knowledge__search_content", Map.of("query", "J.A.R.V.I.S server GPU")));
        turns.add(toolCallTurn("knowledge__list_tree", Map.of()));
        turns.add(toolCallTurn("knowledge__search_content", Map.of("query", "GPU")));
        turns.add(toolCallTurn("knowledge__read_document", Map.of("path", GPU_DOCUMENT_PATH)));
        turns.add(toolCallTurn("web__search_web", Map.of("query", "RTX 4060 Ti 16GB current price")));
        turns.add(toolCallTurn("web__read_web_page", Map.of("url", "https://example.com/rtx-4060-ti-16gb-price")));
        turns.add(textTurn("Serwer ma RTX 4060 Ti 16 GB, aktualna cena ok. 2000 PLN."));
        ScriptedProvider provider = new ScriptedProvider(turns);

        FakeToolManager toolManager = new FakeToolManager();
        toolManager.on("knowledge", "SEARCH_CONTENT", emptySearchResult());
        toolManager.on("knowledge", "LIST_TREE", listTreeResult());
        toolManager.on("knowledge", "READ_DOCUMENT", readResult());
        toolManager.on("web", "SEARCH_WEB", webSearchResult());
        toolManager.on("web", "READ_WEB_PAGE", webPageResult());

        NativeToolLoopService service = new NativeToolLoopService(
                List.of(provider), toolManager, query -> ToolIntent.SEARCH_WEB,
                new ToolRuntimeProperties(true, 4, 8, 2, 30, "native"),
                new NoopCognitiveEventBus(), new ToolRuntimeDebugService(), new ObjectMapper(),
                new NativeToolSchemaMapper(registry())
        );

        ToolCallingResult result = service.execute(new ToolCallingRequest(
                "request-1", "conversation-1",
                "Sprawdz w swojej zapisanej wiedzy, jaka karta graficzna znajduje sie obecnie w serwerze "
                        + "J.A.R.V.I.S. Najpierw znajdz i odczytaj wlasciwy dokument z Knowledge Workspace. "
                        + "Dopiero po ustaleniu dokladnego modelu karty sprawdz w internecie jej aktualna cene.",
                "Identify the server GPU from saved knowledge, then find its current price.",
                "User wants the exact GPU identified before any web pricing lookup.",
                "Base prompt",
                new Brain(BrainType.FAST, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.LOW),
                KnowledgeMode.FAST
        ));

        assertThat(result.handled()).isTrue();
        assertThat(result.steps()).noneMatch(step -> "DUPLICATE_TOOL_CALL".equals(step.action()));
        assertThat(toolManager.executedOperations()).containsExactly(
                "knowledge:SEARCH_CONTENT", "knowledge:LIST_TREE", "knowledge:SEARCH_CONTENT",
                "knowledge:READ_DOCUMENT", "web:SEARCH_WEB", "web:READ_WEB_PAGE");

        int readIndex = toolManager.executedOperations().indexOf("knowledge:READ_DOCUMENT");
        int webIndex = toolManager.executedOperations().indexOf("web:SEARCH_WEB");
        assertThat(readIndex).isLessThan(webIndex);
    }

    @Test
    void identicalOperationAndArgumentsRepeatedWithoutNewInformationIsStillBlocked() {
        Deque<ModelResponse> turns = new ArrayDeque<>();
        turns.add(toolCallTurn("knowledge__search_content", Map.of("query", "GPU")));
        turns.add(toolCallTurn("knowledge__search_content", Map.of("query", "GPU")));
        turns.add(toolCallTurn("knowledge__search_content", Map.of("query", "GPU")));
        turns.add(textTurn("Nie udalo mi sie znalezc dodatkowych informacji."));
        ScriptedProvider provider = new ScriptedProvider(turns);

        FakeToolManager toolManager = new FakeToolManager();
        toolManager.on("knowledge", "SEARCH_CONTENT", emptySearchResult());

        NativeToolLoopService service = new NativeToolLoopService(
                List.of(provider), toolManager, query -> ToolIntent.SEARCH_KNOWLEDGE,
                new ToolRuntimeProperties(true, 4, 8, 2, 30, "native"),
                new NoopCognitiveEventBus(), new ToolRuntimeDebugService(), new ObjectMapper(),
                new NativeToolSchemaMapper(registry())
        );

        ToolCallingResult result = service.execute(new ToolCallingRequest(
                "request-2", "conversation-1", "Szukaj GPU", "Search for GPU repeatedly", "test",
                "Base prompt", new Brain(BrainType.FAST, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.LOW),
                KnowledgeMode.FAST
        ));

        assertThat(result.handled()).isTrue();
        // Only the first identical call actually executes; the repeats are blocked before reaching the tool.
        assertThat(toolManager.executedOperations()).containsExactly("knowledge:SEARCH_CONTENT");
        assertThat(result.steps()).filteredOn(step -> "DUPLICATE_TOOL_CALL".equals(step.action())).hasSize(2);
    }

    private static ModelResponse toolCallTurn(String name, Map<String, Object> arguments) {
        return new ModelResponse("", "", List.of(new ModelToolCall("call-" + name + "-" + System.nanoTime(), name, arguments)),
                "tool_calls", new ModelUsage(0, 0, 0));
    }

    private static ModelResponse textTurn(String content) {
        return new ModelResponse(content, "", List.of(), "stop", new ModelUsage(0, 0, 0));
    }

    private static ToolResult emptySearchResult() {
        return new ToolResult(true, "knowledge", "SEARCH_CONTENT", "", "", false, List.of(), "Search finished",
                Map.of("result", Map.of("documents", List.of())), "", "", false, "");
    }

    private static ToolResult listTreeResult() {
        return new ToolResult(true, "knowledge", "LIST_TREE", "", "", false, List.of(), "Knowledge tree", Map.of(
                "tree", Map.of("children", List.of(Map.of("relativePath", GPU_DOCUMENT_PATH)))
        ), "", "", false, "");
    }

    private static ToolResult readResult() {
        return new ToolResult(true, "knowledge", "READ_DOCUMENT", "", "", false, List.of(), "Document read", Map.of(
                "path", GPU_DOCUMENT_PATH, "content", GPU_CONTENT, "exists", true
        ), "", "", false, "");
    }

    private static ToolResult webSearchResult() {
        return new ToolResult(true, "web", "SEARCH_WEB", "", "", false, List.of(), "Web search finished", Map.of(
                "results", List.of(Map.of("title", "RTX 4060 Ti 16GB price", "url", "https://example.com/rtx-4060-ti-16gb-price",
                        "snippet", "2000 PLN", "source", "example.com"))
        ), "", "", false, "");
    }

    private static ToolResult webPageResult() {
        return new ToolResult(true, "web", "READ_WEB_PAGE", "", "", false, List.of(), "Web page read", Map.of(
                "url", "https://example.com/rtx-4060-ti-16gb-price", "title", "RTX 4060 Ti 16GB", "content", "Cena: 2000 PLN"
        ), "", "", false, "");
    }

    private static ToolRegistry registry() {
        ToolDefinition knowledge = new ToolDefinition("knowledge", "Manages the Knowledge Workspace.", List.of(
                new ToolOperationDefinition("SEARCH_CONTENT", "Search knowledge content.", List.of(
                        new ToolArgumentDefinition("query", "string", true, "Search query")
                ), false, ToolSafetyLevel.READ),
                new ToolOperationDefinition("LIST_TREE", "List the knowledge tree.", List.of(), false, ToolSafetyLevel.READ),
                new ToolOperationDefinition("READ_DOCUMENT", "Read a document by logical path.", List.of(
                        new ToolArgumentDefinition("path", "string", true, "Logical path")
                ), false, ToolSafetyLevel.READ)
        ));
        ToolDefinition web = new ToolDefinition("web", "Searches the live public web.", List.of(
                new ToolOperationDefinition("SEARCH_WEB", "General web search.", List.of(
                        new ToolArgumentDefinition("query", "string", true, "Search query")
                ), false, ToolSafetyLevel.READ),
                new ToolOperationDefinition("READ_WEB_PAGE", "Read a web page.", List.of(
                        new ToolArgumentDefinition("url", "string", true, "URL")
                ), false, ToolSafetyLevel.READ)
        ));
        return new ToolRegistry() {
            @Override
            public List<ToolDefinition> definitions() {
                return List.of(knowledge, web);
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

        private final List<String> executedOperations = new ArrayList<>();
        private final Map<String, ToolResult> fixtures = new java.util.HashMap<>();

        void on(String tool, String operation, ToolResult result) {
            fixtures.put(tool.toLowerCase(Locale.ROOT) + ":" + operation.toUpperCase(Locale.ROOT), result);
        }

        List<String> executedOperations() {
            return executedOperations;
        }

        @Override
        public List<JarvisTool> listTools() {
            return List.of();
        }

        @Override
        public Optional<JarvisTool> findTool(String name) {
            return "knowledge".equalsIgnoreCase(name) || "web".equalsIgnoreCase(name)
                    ? Optional.of(new PlaceholderTool(name))
                    : Optional.empty();
        }

        @Override
        public ToolResult execute(ToolRequest request) {
            String key = request.toolName().toLowerCase(Locale.ROOT) + ":" + request.operation().toUpperCase(Locale.ROOT);
            executedOperations.add(request.toolName() + ":" + request.operation());
            ToolResult fixture = fixtures.get(key);
            if (fixture == null) {
                throw new AssertionError("Unexpected tool call with no registered fixture: " + key
                        + " arguments=" + request.arguments());
            }
            return fixture;
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
