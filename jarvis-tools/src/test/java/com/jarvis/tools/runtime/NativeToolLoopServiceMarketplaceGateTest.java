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

/**
 * Regression tests for the marketplace-mode invariant: the marketplace listing collector must
 * only ever activate after the model itself issues a {@code web__search_marketplace} tool call.
 * A plain {@code web__search_web} call must never be reinterpreted as marketplace research,
 * even when the query text contains generic words like "market" (this is the exact USD/PLN -> OLX
 * regression scenario).
 */
class NativeToolLoopServiceMarketplaceGateTest {

    @Test
    void currencyQueryContainingWordMarketNeverActivatesMarketplace() {
        Deque<ModelResponse> turns = new ArrayDeque<>();
        turns.add(toolCallTurn("web__search_web", Map.of("query", "USD PLN EUR PLN exchange rate market", "maxResults", 8)));
        turns.add(textTurn("Kurs USD/PLN: 3.95. Kurs EUR/PLN: 4.30."));
        ScriptedProvider provider = new ScriptedProvider(turns);

        ToolResult currencySearchResult = new ToolResult(true, "web", "SEARCH_WEB", "request-1", "conversation-1", false,
                List.of(), "Web search finished", Map.of(
                        "query", "USD PLN EUR PLN exchange rate market",
                        "results", List.of(
                                Map.of("title", "NBP - Kursy walut", "url", "https://nbp.pl/kursy", "snippet", "USD 3.95", "source", "nbp.pl"),
                                Map.of("title", "Bankier.pl - kursy walut", "url", "https://bankier.pl/waluty", "snippet", "EUR 4.30", "source", "bankier.pl")
                        )
                ), "", "", false, "");
        FakeToolManager toolManager = new FakeToolManager(currencySearchResult);

        NativeToolLoopService service = new NativeToolLoopService(
                List.of(provider), toolManager, query -> ToolIntent.SEARCH_WEB,
                new ToolRuntimeProperties(true, 4, 8, 2, 30, "native"),
                new NoopCognitiveEventBus(), new ToolRuntimeDebugService(), new ObjectMapper(),
                new NativeToolSchemaMapper(webRegistry())
        );

        ToolCallingResult result = service.execute(new ToolCallingRequest(
                "request-1", "conversation-1",
                "Sprawdz aktualny kurs USD/PLN i EUR/PLN.",
                "Retrieve current exchange rates for USD/PLN and EUR/PLN.",
                "User asked for current exchange rates.",
                "Base prompt",
                new Brain(BrainType.FAST, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.LOW),
                KnowledgeMode.FAST
        ));

        assertThat(result.handled()).isTrue();
        assertThat(result.results()).hasSize(1);
        ToolResult executed = result.results().get(0);
        assertThat(executed.operation()).isEqualTo("SEARCH_WEB");
        assertThat(executed.data()).doesNotContainKey("marketplaceResearch");
        assertThat(executed.data()).doesNotContainKey("marketplaceListings");
        assertThat(toolManager.executedOperations()).containsExactly("SEARCH_WEB");
    }

    @Test
    void explicitSearchMarketplaceActivatesCollectorWithModelSuppliedRequirements() {
        Deque<ModelResponse> turns = new ArrayDeque<>();
        turns.add(toolCallTurn("web__search_marketplace", Map.of("query", "RTX 3060 12GB", "targetCount", 2, "condition", "USED")));
        turns.add(textTurn("Znalazlem oferty RTX 3060."));
        ScriptedProvider provider = new ScriptedProvider(turns);

        ToolResult marketplaceSearchResult = new ToolResult(true, "web", "SEARCH_MARKETPLACE", "request-2", "conversation-1", false,
                List.of(), "Marketplace search finished", Map.of(
                        "query", "RTX 3060 12GB",
                        "results", List.of()
                ), "", "", false, "");
        FakeToolManager toolManager = new FakeToolManager(marketplaceSearchResult);

        NativeToolLoopService service = new NativeToolLoopService(
                List.of(provider), toolManager, query -> ToolIntent.SEARCH_WEB,
                new ToolRuntimeProperties(true, 4, 8, 2, 30, "native"),
                new NoopCognitiveEventBus(), new ToolRuntimeDebugService(), new ObjectMapper(),
                new NativeToolSchemaMapper(webRegistry())
        );

        ToolCallingResult result = service.execute(new ToolCallingRequest(
                "request-2", "conversation-1",
                "Znajdz 2 uzywane RTX 3060 12GB.",
                "Find concrete used RTX 3060 12GB listings.",
                "User explicitly asked to browse marketplace listings.",
                "Base prompt",
                new Brain(BrainType.FAST, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.LOW),
                KnowledgeMode.FAST
        ));

        assertThat(result.handled()).isTrue();
        assertThat(result.results()).hasSize(1);
        ToolResult executed = result.results().get(0);
        assertThat(executed.operation()).isEqualTo("SEARCH_MARKETPLACE");
        assertThat(executed.data().get("marketplaceResearch")).isEqualTo(true);
        assertThat(executed.data().get("targetListingCount")).isEqualTo(2);
    }

    @Test
    void searchMarketplaceFollowedByUnrelatedSearchWebDoesNotContaminateTheWebResult() {
        Deque<ModelResponse> turns = new ArrayDeque<>();
        turns.add(toolCallTurn("web__search_marketplace", Map.of("query", "sklepy Warszawa", "targetCount", 3)));
        turns.add(toolCallTurn("web__search_web", Map.of("query", "Nowa Wola 05-500 wspolrzedne geograficzne")));
        turns.add(textTurn("Oto zoptymalizowana trasa dla podanych adresow."));
        ScriptedProvider provider = new ScriptedProvider(turns);

        ToolResult marketplaceResult = new ToolResult(true, "web", "SEARCH_MARKETPLACE", "request-3", "conversation-1",
                false, List.of(), "Marketplace search finished", Map.of("query", "sklepy Warszawa", "results", List.of()),
                "", "", false, "");
        ToolResult geocodeSearchResult = new ToolResult(true, "web", "SEARCH_WEB", "request-3", "conversation-1",
                false, List.of(), "Web search finished", Map.of(
                        "query", "Nowa Wola 05-500 wspolrzedne geograficzne",
                        "results", List.of(Map.of("title", "Nowa Wola", "url", "https://example.com/nowa-wola", "snippet", "52.5, 20.8", "source", "example.com"))
                ), "", "", false, "");
        RoutingFakeToolManager toolManager = new RoutingFakeToolManager(Map.of(
                "SEARCH_MARKETPLACE", marketplaceResult,
                "SEARCH_WEB", geocodeSearchResult
        ));

        NativeToolLoopService service = new NativeToolLoopService(
                List.of(provider), toolManager, query -> ToolIntent.SEARCH_WEB,
                new ToolRuntimeProperties(true, 4, 8, 2, 30, "native"),
                new NoopCognitiveEventBus(), new ToolRuntimeDebugService(), new ObjectMapper(),
                new NativeToolSchemaMapper(webRegistry())
        );

        ToolCallingResult result = service.execute(new ToolCallingRequest(
                "request-3", "conversation-1",
                "Zaplanuj trase do sklepow zaczynajac od Nowa Wola 05-500.",
                "Geocode the start point and store addresses to compute an optimal route.",
                "User asked for a visiting order.",
                "Base prompt",
                new Brain(BrainType.FAST, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.LOW),
                KnowledgeMode.FAST
        ));

        assertThat(result.handled()).isTrue();
        assertThat(result.results()).hasSize(2);
        ToolResult marketplaceExecuted = result.results().get(0);
        ToolResult webSearchExecuted = result.results().get(1);
        assertThat(marketplaceExecuted.operation()).isEqualTo("SEARCH_MARKETPLACE");
        assertThat(marketplaceExecuted.data().get("marketplaceResearch")).isEqualTo(true);

        assertThat(webSearchExecuted.operation()).isEqualTo("SEARCH_WEB");
        assertThat(webSearchExecuted.data()).doesNotContainKey("marketplaceResearch");
        assertThat(webSearchExecuted.data()).doesNotContainKey("marketplaceListings");
    }

    private static ModelResponse toolCallTurn(String name, Map<String, Object> arguments) {
        return new ModelResponse("", "", List.of(new ModelToolCall("call-1", name, arguments)), "tool_calls", new ModelUsage(0, 0, 0));
    }

    private static ModelResponse textTurn(String content) {
        return new ModelResponse(content, "", List.of(), "stop", new ModelUsage(0, 0, 0));
    }

    private static ToolRegistry webRegistry() {
        ToolDefinition definition = new ToolDefinition("web", "Web search", List.of(
                new ToolOperationDefinition("SEARCH_WEB", "Search web", List.of(
                        new ToolArgumentDefinition("query", "string", true, "Search query"),
                        new ToolArgumentDefinition("maxResults", "number", false, "Maximum results")
                ), false, ToolSafetyLevel.READ),
                new ToolOperationDefinition("READ_WEB_PAGE", "Read web page", List.of(
                        new ToolArgumentDefinition("url", "string", true, "URL")
                ), false, ToolSafetyLevel.READ),
                new ToolOperationDefinition("SEARCH_MARKETPLACE", "Find and verify marketplace listings", List.of(
                        new ToolArgumentDefinition("query", "string", true, "Product query"),
                        new ToolArgumentDefinition("targetCount", "number", false, "Target listing count"),
                        new ToolArgumentDefinition("condition", "string", false, "NEW or USED"),
                        new ToolArgumentDefinition("domains", "string", false, "Restricted domains")
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
        private final java.util.List<String> executedOperations = new java.util.ArrayList<>();

        private FakeToolManager(ToolResult scriptedResult) {
            this.scriptedResult = scriptedResult;
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
            return "web".equalsIgnoreCase(name) ? Optional.of(new PlaceholderWebTool()) : Optional.empty();
        }

        @Override
        public ToolResult execute(ToolRequest request) {
            executedOperations.add(request.operation());
            return scriptedResult;
        }
    }

    private static final class RoutingFakeToolManager implements ToolManager {

        private final Map<String, ToolResult> resultsByOperation;

        private RoutingFakeToolManager(Map<String, ToolResult> resultsByOperation) {
            this.resultsByOperation = resultsByOperation;
        }

        @Override
        public List<JarvisTool> listTools() {
            return List.of();
        }

        @Override
        public Optional<JarvisTool> findTool(String name) {
            return "web".equalsIgnoreCase(name) ? Optional.of(new PlaceholderWebTool()) : Optional.empty();
        }

        @Override
        public ToolResult execute(ToolRequest request) {
            ToolResult scripted = resultsByOperation.get(request.operation());
            if (scripted == null) {
                throw new IllegalStateException("No scripted result for operation " + request.operation());
            }
            return scripted;
        }
    }

    private static final class PlaceholderWebTool implements JarvisTool {

        @Override
        public String getName() {
            return "web";
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
