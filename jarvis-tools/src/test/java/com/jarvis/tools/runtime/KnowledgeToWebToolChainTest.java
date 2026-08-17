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
import java.util.Objects;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for the cross-tool "identify from Knowledge, then price on the web" flow.
 *
 * <p>These tests verify what Core is actually responsible for guaranteeing: it never skips,
 * reorders, or auto-chains tool calls on the model's behalf, it relays real document content
 * unmodified, and knowledge lookup failures come back as diagnostic ToolResults instead of a
 * silent fallback. Whether the model itself picks the right document, uses the real GPU name in
 * its web query, or invents a wrong one is model behavior — not something Core code can assert;
 * these tests prove the infrastructure does not get in the way of, or paper over, that decision.
 */
class KnowledgeToWebToolChainTest {

    private static final String GPU_DOCUMENT_PATH = "hardware/graphics_card.txt";
    private static final String GPU_CONTENT = "RTX 4060 Ti 16 GB";

    @Test
    void knowledgeIdentityResolvedBeforeMarketplaceSearchInCorrectOrder() {
        Deque<ModelResponse> turns = new ArrayDeque<>();
        turns.add(toolCallTurn("knowledge__search_content", Map.of("query", "karta graficzna serwer JARVIS")));
        turns.add(toolCallTurn("knowledge__read_document", Map.of("path", GPU_DOCUMENT_PATH)));
        turns.add(toolCallTurn("web__search_marketplace", Map.of("query", "RTX 4060 Ti 16GB cena")));
        turns.add(textTurn("Serwer J.A.R.V.I.S. ma RTX 4060 Ti 16 GB. Aktualna cena: ok. 2000 PLN."));
        ScriptedProvider provider = new ScriptedProvider(turns);

        FakeToolManager toolManager = new FakeToolManager();
        toolManager.onKnowledgeSearch(searchResult("SEARCH_CONTENT", GPU_DOCUMENT_PATH));
        toolManager.onKnowledgeRead(GPU_DOCUMENT_PATH, readResult(GPU_DOCUMENT_PATH, GPU_CONTENT));
        toolManager.onWebSearch("SEARCH_MARKETPLACE", marketplaceResult());

        NativeToolLoopService service = new NativeToolLoopService(
                List.of(provider), toolManager, query -> ToolIntent.NO_TOOL,
                new ToolRuntimeProperties(true, 4, 8, 2, 30, "native"),
                new NoopCognitiveEventBus(), new ToolRuntimeDebugService(), new ObjectMapper(),
                new NativeToolSchemaMapper(registry()),
                new com.jarvis.tools.dataset.StoreAuditDatasetService(new NoopCognitiveEventBus())
        );

        ToolCallingResult result = service.execute(new ToolCallingRequest(
                "request-1", "conversation-1",
                "Sprawdz w zapisanej wiedzy jaka karta graficzna znajduje sie w serwerze J.A.R.V.I.S., "
                        + "a nastepnie sprawdz jej aktualna cene.",
                "Identify the server GPU from saved knowledge, then find its current price.",
                "User wants the server's GPU identified from knowledge before pricing it.",
                "Base prompt",
                new Brain(BrainType.FAST, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.LOW),
                KnowledgeMode.FAST
        ));

        assertThat(result.handled()).isTrue();
        assertThat(toolManager.executedOperations()).containsExactly(
                "knowledge:SEARCH_CONTENT", "knowledge:READ_DOCUMENT", "web:SEARCH_MARKETPLACE");

        int knowledgeReadIndex = toolManager.executedOperations().indexOf("knowledge:READ_DOCUMENT");
        int marketplaceIndex = toolManager.executedOperations().indexOf("web:SEARCH_MARKETPLACE");
        assertThat(knowledgeReadIndex).isLessThan(marketplaceIndex);

        ToolResult readResult = result.results().get(1);
        assertThat(readResult.data().get("content")).isEqualTo(GPU_CONTENT);
        assertThat(toolManager.lastWebQuery()).contains("RTX 4060 Ti");
        assertThat(toolManager.lastWebQuery()).doesNotContain("3080");
    }

    @Test
    void knowledgeLookupFailureReturnsDiagnosticErrorAndNeverAutoChainsToWeb() {
        Deque<ModelResponse> turns = new ArrayDeque<>();
        turns.add(toolCallTurn("knowledge__read_document", Map.of("path", GPU_DOCUMENT_PATH)));
        turns.add(textTurn("Nie znalazlem informacji o karcie graficznej w zapisanej wiedzy."));
        ScriptedProvider provider = new ScriptedProvider(turns);

        FakeToolManager toolManager = new FakeToolManager();
        toolManager.onKnowledgeRead(GPU_DOCUMENT_PATH, notFoundResult(GPU_DOCUMENT_PATH));

        NativeToolLoopService service = new NativeToolLoopService(
                List.of(provider), toolManager, query -> ToolIntent.NO_TOOL,
                new ToolRuntimeProperties(true, 4, 8, 2, 30, "native"),
                new NoopCognitiveEventBus(), new ToolRuntimeDebugService(), new ObjectMapper(),
                new NativeToolSchemaMapper(registry()),
                new com.jarvis.tools.dataset.StoreAuditDatasetService(new NoopCognitiveEventBus())
        );

        ToolCallingResult result = service.execute(new ToolCallingRequest(
                "request-2", "conversation-1",
                "Sprawdz w zapisanej wiedzy jaka karta graficzna znajduje sie w serwerze J.A.R.V.I.S., "
                        + "a nastepnie sprawdz jej aktualna cene.",
                "Identify the server GPU from saved knowledge, then find its current price.",
                "User wants the server's GPU identified from knowledge before pricing it.",
                "Base prompt",
                new Brain(BrainType.FAST, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.LOW),
                KnowledgeMode.FAST
        ));

        assertThat(result.handled()).isTrue();
        assertThat(toolManager.executedOperations()).containsExactly("knowledge:READ_DOCUMENT");
        assertThat(toolManager.executedOperations()).noneMatch(op -> op.startsWith("web:"));

        ToolResult failed = result.results().get(0);
        assertThat(failed.success()).isFalse();
        assertThat(failed.errorCode()).isEqualTo("DOCUMENT_NOT_FOUND");
        assertThat(failed.errorMessage()).isNotBlank();
        assertThat(failed.errorMessage().toLowerCase(java.util.Locale.ROOT)).doesNotContain("unknown error");
        assertThat(failed.errorMessage()).contains(GPU_DOCUMENT_PATH);
    }

    private static ModelResponse toolCallTurn(String name, Map<String, Object> arguments) {
        return new ModelResponse("", "", List.of(new ModelToolCall("call-" + name, name, arguments)), "tool_calls", new ModelUsage(0, 0, 0));
    }

    private static ModelResponse textTurn(String content) {
        return new ModelResponse(content, "", List.of(), "stop", new ModelUsage(0, 0, 0));
    }

    private static ToolResult searchResult(String operation, String matchedPath) {
        return new ToolResult(true, "knowledge", operation, "", "", false, List.of(), "Search finished", Map.of(
                "result", Map.of("documents", List.of(Map.of("relativePath", matchedPath, "title", "graphics_card")))
        ), "", "", false, "");
    }

    private static ToolResult readResult(String path, String content) {
        return new ToolResult(true, "knowledge", "READ_DOCUMENT", "", "", false, List.of(), "Document read", Map.of(
                "path", path, "content", content, "exists", true
        ), "", "", false, "");
    }

    private static ToolResult notFoundResult(String path) {
        return new ToolResult(false, "knowledge", "READ_DOCUMENT", "", "", false, List.of(), "Document not found", Map.of(
                "path", path, "exists", false
        ), "DOCUMENT_NOT_FOUND", "Document not found: " + path, false, "");
    }

    private static ToolResult marketplaceResult() {
        return new ToolResult(true, "web", "SEARCH_MARKETPLACE", "", "", false, List.of(), "Marketplace search finished", Map.of(
                "results", List.of(), "marketplaceResearch", true
        ), "", "", false, "");
    }

    private static ToolRegistry registry() {
        ToolDefinition knowledge = new ToolDefinition("knowledge", "Manages the Knowledge Workspace.", List.of(
                new ToolOperationDefinition("SEARCH_CONTENT", "Search knowledge content.", List.of(
                        new ToolArgumentDefinition("query", "string", true, "Search query")
                ), false, ToolSafetyLevel.READ),
                new ToolOperationDefinition("READ_DOCUMENT", "Read a document by logical path.", List.of(
                        new ToolArgumentDefinition("path", "string", true, "Logical path")
                ), false, ToolSafetyLevel.READ)
        ));
        ToolDefinition web = new ToolDefinition("web", "Searches the live public web.", List.of(
                new ToolOperationDefinition("SEARCH_WEB", "General web search.", List.of(
                        new ToolArgumentDefinition("query", "string", true, "Search query")
                ), false, ToolSafetyLevel.READ),
                new ToolOperationDefinition("SEARCH_MARKETPLACE", "Find and verify marketplace listings.", List.of(
                        new ToolArgumentDefinition("query", "string", true, "Product query")
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

    /**
     * Dispatches canned results by exact (tool, operation, key-argument) match. Throws if the
     * model requests something no fixture was registered for, so an unexpected/invented call
     * fails the test loudly instead of silently returning a default success.
     */
    private static final class FakeToolManager implements ToolManager {

        private final List<String> executedOperations = new ArrayList<>();
        private ToolResult knowledgeSearchResult;
        private final java.util.Map<String, ToolResult> knowledgeReadsByPath = new java.util.HashMap<>();
        private final java.util.Map<String, ToolResult> webResultsByOperation = new java.util.HashMap<>();
        private String lastWebQuery = "";

        void onKnowledgeSearch(ToolResult result) {
            this.knowledgeSearchResult = result;
        }

        void onKnowledgeRead(String path, ToolResult result) {
            knowledgeReadsByPath.put(path, result);
        }

        void onWebSearch(String operation, ToolResult result) {
            webResultsByOperation.put(operation, result);
        }

        List<String> executedOperations() {
            return executedOperations;
        }

        String lastWebQuery() {
            return lastWebQuery;
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
            executedOperations.add(request.toolName() + ":" + request.operation());
            if ("knowledge".equalsIgnoreCase(request.toolName())) {
                if ("SEARCH_CONTENT".equalsIgnoreCase(request.operation()) || "SEARCH_DOCUMENT".equalsIgnoreCase(request.operation())) {
                    if (knowledgeSearchResult == null) {
                        throw new AssertionError("Unexpected knowledge search call: no fixture registered");
                    }
                    return knowledgeSearchResult;
                }
                if ("READ_DOCUMENT".equalsIgnoreCase(request.operation())) {
                    String path = Objects.toString(request.arguments().get("path"), "");
                    ToolResult fixture = knowledgeReadsByPath.get(path);
                    if (fixture == null) {
                        throw new AssertionError("Unexpected knowledge read for unregistered path: " + path);
                    }
                    return fixture;
                }
            }
            if ("web".equalsIgnoreCase(request.toolName())) {
                lastWebQuery = Objects.toString(request.arguments().get("query"), "");
                ToolResult fixture = webResultsByOperation.get(request.operation().toUpperCase(java.util.Locale.ROOT));
                if (fixture == null) {
                    throw new AssertionError("Unexpected web call before knowledge identity was resolved: "
                            + request.operation() + " query=" + lastWebQuery);
                }
                return fixture;
            }
            throw new AssertionError("Unexpected tool call: " + request.toolName() + ":" + request.operation());
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
