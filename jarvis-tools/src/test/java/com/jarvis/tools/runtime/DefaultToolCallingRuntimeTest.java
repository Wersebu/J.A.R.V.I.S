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
        assertThat(String.valueOf(executed.get().arguments().get("query"))).contains("USD to PLN");
        assertThat(executions).hasValue(1);
    }

    @Test
    void mapsMainModelWebRequestWithoutSecondModelSelectionCall() {
        AtomicReference<ToolRequest> executed = new AtomicReference<>();
        AtomicInteger executions = new AtomicInteger();
        AtomicInteger modelCalls = new AtomicInteger();
        DefaultToolCallingRuntime runtime = new DefaultToolCallingRuntime(
                List.of(new StubProvider("""
                        {"action":"NO_TOOL","reason":"Should not be asked during direct web mapping."}
                        """, modelCalls)),
                new StubToolManager(executed, executions),
                webRegistry(),
                query -> ToolIntent.SEARCH_WEB,
                new ToolRuntimeProperties(true, 2, 2, 1, 30),
                new NoopCognitiveEventBus(),
                new ToolRuntimeDebugService(),
                new ObjectMapper()
        );

        ToolCallingResult result = runtime.execute(new ToolCallingRequest(
                "request-2",
                "conversation-1",
                "siemka po ile sa karty rtx 4070ti uzywane?",
                "Search the web for the current market price of NVIDIA GeForce RTX 4070 Ti graphics cards.",
                "The user needs current used-market pricing.",
                "Base prompt",
                new Brain(BrainType.FAST, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.LOW),
                KnowledgeMode.FAST
        ));

        assertThat(result.handled()).isTrue();
        assertThat(modelCalls).hasValue(0);
        assertThat(executions).hasValue(1);
        assertThat(executed.get()).isNotNull();
        assertThat(executed.get().toolName()).isEqualTo("web");
        assertThat(executed.get().operation()).isEqualTo("SEARCH_WEB");
        assertThat(String.valueOf(executed.get().arguments().get("query"))).containsIgnoringCase("RTX 4070 ti");
        assertThat(String.valueOf(executed.get().arguments().get("query"))).contains("Allegro");
        assertThat(String.valueOf(executed.get().arguments().get("query"))).doesNotContain("siemka");
    }

    @Test
    void retriesWebSearchWhenResultsAreIrrelevant() {
        AtomicReference<ToolRequest> executed = new AtomicReference<>();
        AtomicInteger executions = new AtomicInteger();
        AtomicInteger modelCalls = new AtomicInteger();
        DefaultToolCallingRuntime runtime = new DefaultToolCallingRuntime(
                List.of(new StubProvider("""
                        {"action":"TOOL_CALL","tool":"web","operation":"SEARCH_WEB","arguments":{"query":"site:allegro.pl RTX 4060 Ti karta graficzna","maxResults":5},"reason":"Previous results were irrelevant, search the requested marketplace directly."}
                        """, modelCalls)),
                new StubToolManager(executed, executions) {
                    @Override
                    public ToolResult execute(ToolRequest request) {
                        executed.set(request);
                        int call = executions.incrementAndGet();
                        if (call == 1) {
                            return webResult(request, "Instagram cats", "https://instagram.com/p/not-a-gpu", "not about the requested GPU");
                        }
                        return webResult(request, "Allegro RTX 4060 Ti", "https://allegro.pl/oferta/rtx-4060-ti", "RTX 4060 Ti karta graficzna");
                    }
                },
                webRegistry(),
                query -> ToolIntent.SEARCH_WEB,
                new ToolRuntimeProperties(true, 4, 4, 1, 30),
                new NoopCognitiveEventBus(),
                new ToolRuntimeDebugService(),
                new ObjectMapper()
        );

        ToolCallingResult result = runtime.execute(new ToolCallingRequest(
                "request-3",
                "conversation-1",
                "daj link do konkretnej karty 4060ti z allegro",
                "Search for a current Allegro listing of a used RTX 4060 Ti graphics card and provide the URL.",
                "The user needs a concrete marketplace listing.",
                "Base prompt",
                new Brain(BrainType.FAST, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.LOW),
                KnowledgeMode.FAST
        ));

        assertThat(result.handled()).isTrue();
        assertThat(executions).hasValue(2);
        assertThat(modelCalls).hasValue(1);
        assertThat(result.results().getFirst().data()).containsEntry("sourceQualityAccepted", false);
        assertThat(result.results().get(1).data()).containsEntry("sourceQualityAccepted", true);
        assertThat(executed.get().arguments()).containsEntry("query", "site:allegro.pl RTX 4060 Ti karta graficzna");
    }

    @Test
    void readsAcceptedWebResultWhenRepeatedEnvelopeFollowsWeakSnippets() {
        AtomicReference<ToolRequest> executed = new AtomicReference<>();
        AtomicInteger executions = new AtomicInteger();
        AtomicInteger modelCalls = new AtomicInteger();
        DefaultToolCallingRuntime runtime = new DefaultToolCallingRuntime(
                List.of(new SequentialStubProvider(modelCalls,
                        """
                        {
                          "type": "TOOL_REQUEST",
                          "goal": "Search the live web for current used RTX 4060 Ti 16GB prices.",
                          "reason": "Need current prices from external sources."
                        }
                        """,
                        """
                        {
                          "type": "TOOL_REQUEST",
                          "goal": "Search the live web for current used RTX 4060 Ti 16GB prices.",
                          "reason": "Need current prices from external sources."
                        }
                        """,
                        "{\"action\":\"FINAL_ANSWER\",\"answer\":\"Enough evidence was found.\"}"
                )),
                new StubToolManager(executed, executions) {
                    @Override
                    public ToolResult execute(ToolRequest request) {
                        executed.set(request);
                        int call = executions.incrementAndGet();
                        if (call == 1) {
                            return webResult(request, "Allegro RTX 4060 Ti 16GB", "https://allegro.pl/oferta/rtx-4060-ti",
                                    "NVIDIA GeForce RTX 4060 Ti 16GB karta graficzna");
                        }
                        assertThat(request.operation()).isEqualTo("READ_WEB_PAGE");
                        return new ToolResult(true, "web", "READ_WEB_PAGE", request.requestId(), request.conversationId(), false,
                                List.of("web:page"), "Read page", Map.of(
                                "url", request.arguments().get("url"),
                                "title", "Allegro RTX 4060 Ti 16GB",
                                "content", "RTX 4060 Ti 16GB cena 1299 PLN",
                                "characters", 33
                        ), "", "", false, "");
                    }
                },
                webRegistry(),
                query -> ToolIntent.SEARCH_WEB,
                new ToolRuntimeProperties(true, 4, 4, 1, 30),
                new NoopCognitiveEventBus(),
                new ToolRuntimeDebugService(),
                new ObjectMapper()
        );

        ToolCallingResult result = runtime.execute(new ToolCallingRequest(
                "request-4",
                "conversation-1",
                "sprawdz ceny z rynku wtornego dla rtx 4060ti 16 gb",
                "Search the live web for current used Nvidia GeForce RTX 4060 Ti GPUs with 16 GB VRAM and retrieve current market prices.",
                "The user needs current secondary market pricing.",
                "Base prompt",
                new Brain(BrainType.FAST, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.LOW),
                KnowledgeMode.FAST
        ));

        assertThat(result.handled()).isTrue();
        assertThat(executions).hasValue(2);
        assertThat(executed.get().operation()).isEqualTo("READ_WEB_PAGE");
        assertThat(executed.get().arguments()).containsEntry("url", "https://allegro.pl/oferta/rtx-4060-ti");
    }

    @Test
    void readsUrlDirectlyWhenUserProvidesSpecificWebPage() {
        AtomicReference<ToolRequest> executed = new AtomicReference<>();
        AtomicInteger executions = new AtomicInteger();
        AtomicInteger modelCalls = new AtomicInteger();
        DefaultToolCallingRuntime runtime = new DefaultToolCallingRuntime(
                List.of(new StubProvider("{\"action\":\"FINAL_ANSWER\",\"answer\":\"Done\"}", modelCalls)),
                new StubToolManager(executed, executions) {
                    @Override
                    public ToolResult execute(ToolRequest request) {
                        executed.set(request);
                        executions.incrementAndGet();
                        return new ToolResult(true, "web", "READ_WEB_PAGE", request.requestId(), request.conversationId(), false,
                                List.of("web:page"), "Read page", Map.of(
                                "url", request.arguments().get("url"),
                                "title", "OLX RTX 4060 Ti",
                                "content", "RTX 4060 Ti cena 1200 zl",
                                "characters", 25
                        ), "", "", false, "");
                    }
                },
                webRegistry(),
                query -> ToolIntent.SEARCH_WEB,
                new ToolRuntimeProperties(true, 4, 4, 1, 30),
                new NoopCognitiveEventBus(),
                new ToolRuntimeDebugService(),
                new ObjectMapper()
        );

        ToolCallingResult result = runtime.execute(new ToolCallingRequest(
                "request-5",
                "conversation-1",
                "ile to kosztuje? https://www.olx.pl/d/oferta/gigabyte-rtx-4060-ti-eagle-8-gb-CID99-ID1bAbQf.html?search_reason=search%7Corganic",
                "Retrieve the current price of the card listed at https://www.olx.pl/d/oferta/gigabyte-rtx-4060-ti-eagle-8-gb-CID99-ID1bAbQf.html?search_reason=search%7Corganic",
                "Need price from OLX link.",
                "Base prompt",
                new Brain(BrainType.FAST, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.LOW),
                KnowledgeMode.FAST
        ));

        assertThat(result.handled()).isTrue();
        assertThat(executions).hasValue(1);
        assertThat(modelCalls).hasValue(0);
        assertThat(executed.get().operation()).isEqualTo("READ_WEB_PAGE");
        assertThat(String.valueOf(executed.get().arguments().get("url"))).startsWith("https://www.olx.pl/d/oferta/");
    }

    @Test
    void readsBestSearchResultWhenModelMentionsWebBrowseWithoutJson() {
        AtomicReference<ToolRequest> executed = new AtomicReference<>();
        AtomicInteger executions = new AtomicInteger();
        AtomicInteger modelCalls = new AtomicInteger();
        DefaultToolCallingRuntime runtime = new DefaultToolCallingRuntime(
                List.of(new SequentialStubProvider(modelCalls,
                        "Need to retrieve page. Use web_browse tool. So output TOOL_REQUEST for browsing the specific URL.",
                        "Repair failed: use web_browse.",
                        "{\"action\":\"FINAL_ANSWER\",\"answer\":\"Done\"}"
                )),
                new StubToolManager(executed, executions) {
                    @Override
                    public ToolResult execute(ToolRequest request) {
                        executed.set(request);
                        int call = executions.incrementAndGet();
                        if (call == 1) {
                            return webResult(request, "rtx 4060 ti 8gb - OLX", "https://www.olx.pl/d/oferta/rtx-4060-ti",
                                    "rtx 4060 ti 8gb karta graficzna");
                        }
                        assertThat(request.operation()).isEqualTo("READ_WEB_PAGE");
                        return new ToolResult(true, "web", "READ_WEB_PAGE", request.requestId(), request.conversationId(), false,
                                List.of("web:page"), "Read page", Map.of(
                                "url", request.arguments().get("url"),
                                "title", "OLX RTX 4060 Ti",
                                "content", "RTX 4060 Ti cena 1200 zl",
                                "characters", 25
                        ), "", "", false, "");
                    }
                },
                webRegistry(),
                query -> ToolIntent.SEARCH_WEB,
                new ToolRuntimeProperties(true, 4, 4, 1, 30),
                new NoopCognitiveEventBus(),
                new ToolRuntimeDebugService(),
                new ObjectMapper()
        );

        ToolCallingResult result = runtime.execute(new ToolCallingRequest(
                "request-6",
                "conversation-1",
                "podaj cene rtx 4060ti z olx",
                "Search OLX for used RTX 4060 Ti listings and retrieve the price.",
                "Need current marketplace price.",
                "Base prompt",
                new Brain(BrainType.FAST, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.LOW),
                KnowledgeMode.FAST
        ));

        assertThat(result.handled()).isTrue();
        assertThat(executions).hasValue(2);
        assertThat(executed.get().operation()).isEqualTo("READ_WEB_PAGE");
        assertThat(executed.get().arguments()).containsEntry("url", "https://www.olx.pl/d/oferta/rtx-4060-ti");
    }

    @Test
    void readsBestSearchResultWhenNextWebStepIsEmptyAfterWeakSnippets() {
        AtomicReference<ToolRequest> executed = new AtomicReference<>();
        AtomicInteger executions = new AtomicInteger();
        AtomicInteger modelCalls = new AtomicInteger();
        DefaultToolCallingRuntime runtime = new DefaultToolCallingRuntime(
                List.of(new SequentialStubProvider(modelCalls, "", "{\"action\":\"NO_TOOL\",\"reason\":\"No safe action.\"}")),
                new StubToolManager(executed, executions) {
                    @Override
                    public ToolResult execute(ToolRequest request) {
                        executed.set(request);
                        int call = executions.incrementAndGet();
                        if (call == 1) {
                            return webResult(request, "RTX 4060 Ti Uzywana - Niska cena na Allegro",
                                    "https://allegro.pl/oferta/rtx-4060-ti",
                                    "NVIDIA GeForce RTX 4060 Ti 16GB karta graficzna");
                        }
                        assertThat(request.operation()).isEqualTo("READ_WEB_PAGE");
                        return new ToolResult(true, "web", "READ_WEB_PAGE", request.requestId(), request.conversationId(), false,
                                List.of("web:page"), "Read page", Map.of(
                                "url", request.arguments().get("url"),
                                "title", "RTX 4060 Ti Uzywana - Niska cena na Allegro",
                                "content", "RTX 4060 Ti 16GB cena 1250 PLN",
                                "characters", 31
                        ), "", "", false, "");
                    }
                },
                webRegistry(),
                query -> ToolIntent.SEARCH_WEB,
                new ToolRuntimeProperties(true, 4, 4, 1, 30),
                new NoopCognitiveEventBus(),
                new ToolRuntimeDebugService(),
                new ObjectMapper()
        );

        ToolCallingResult result = runtime.execute(new ToolCallingRequest(
                "request-7",
                "conversation-1",
                "polska i pln",
                "Search the live web for current used Nvidia GeForce RTX 4060 Ti GPUs with 16 GB VRAM and retrieve current market prices.",
                "Need current marketplace price in Poland.",
                "Base prompt",
                new Brain(BrainType.FAST, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.LOW),
                KnowledgeMode.FAST
        ));

        assertThat(result.handled()).isTrue();
        assertThat(executions).hasValue(2);
        assertThat(modelCalls).hasValue(2);
        assertThat(executed.get().operation()).isEqualTo("READ_WEB_PAGE");
        assertThat(executed.get().arguments()).containsEntry("url", "https://allegro.pl/oferta/rtx-4060-ti");
    }

    @Test
    void usesMainModelToolGoalWhenUserReplyOnlyConfirmsPreviousClarification() {
        AtomicReference<ToolRequest> executed = new AtomicReference<>();
        AtomicInteger executions = new AtomicInteger();
        AtomicInteger modelCalls = new AtomicInteger();
        DefaultToolCallingRuntime runtime = new DefaultToolCallingRuntime(
                List.of(new StubProvider("{\"action\":\"NO_TOOL\",\"reason\":\"Should not be asked during direct web mapping.\"}", modelCalls)),
                new StubToolManager(executed, executions),
                webRegistry(),
                query -> ToolIntent.NO_TOOL,
                new ToolRuntimeProperties(true, 2, 2, 1, 30),
                new NoopCognitiveEventBus(),
                new ToolRuntimeDebugService(),
                new ObjectMapper()
        );

        ToolCallingResult result = runtime.execute(new ToolCallingRequest(
                "request-8",
                "conversation-1",
                "tak +/-",
                "Retrieve up-to-date listings and price ranges for used RTX3060 Aorus GPUs in Poland, focusing on OLX and Allegro.",
                "User confirmed that approximate current prices are enough; need current web data.",
                "Base prompt",
                new Brain(BrainType.FAST, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.LOW),
                KnowledgeMode.FAST
        ));

        assertThat(result.handled()).isTrue();
        assertThat(modelCalls).hasValue(0);
        assertThat(executions).hasValue(1);
        assertThat(executed.get()).isNotNull();
        assertThat(executed.get().toolName()).isEqualTo("web");
        assertThat(executed.get().operation()).isEqualTo("SEARCH_WEB");
        assertThat(String.valueOf(executed.get().arguments().get("query"))).containsIgnoringCase("RTX 3060");
        assertThat(String.valueOf(executed.get().arguments().get("query"))).containsIgnoringCase("Aorus");
        assertThat(String.valueOf(executed.get().arguments().get("query"))).contains("OLX");
    }

    @Test
    void continuesWebResearchWhenReadPageDoesNotContainRequestedPrice() {
        AtomicReference<ToolRequest> executed = new AtomicReference<>();
        AtomicInteger executions = new AtomicInteger();
        AtomicInteger modelCalls = new AtomicInteger();
        DefaultToolCallingRuntime runtime = new DefaultToolCallingRuntime(
                List.of(new SequentialStubProvider(modelCalls,
                        """
                        {"action":"TOOL_CALL","tool":"web","operation":"READ_WEB_PAGE","arguments":{"url":"https://olx.pl/oferta/rtx-3060-ti"},"reason":"Search snippets were relevant but incomplete, read the best page."}
                        """,
                        """
                        {"action":"TOOL_CALL","tool":"web","operation":"SEARCH_WEB","arguments":{"query":"RTX 3060 Ti uzywana cena PLN Allegro OLX","maxResults":5},"reason":"The page did not contain a price, search again with a broader marketplace query."}
                        """
                )),
                new StubToolManager(executed, executions) {
                    @Override
                    public ToolResult execute(ToolRequest request) {
                        executed.set(request);
                        int call = executions.incrementAndGet();
                        if (call == 1) {
                            return webResult(request, "rtx 3060 ti - Komputery w dobrej cenie - OLX",
                                    "https://olx.pl/oferta/rtx-3060-ti",
                                    "RTX 3060 Ti karta graficzna uzywana OLX");
                        }
                        if (call == 2) {
                            assertThat(request.operation()).isEqualTo("READ_WEB_PAGE");
                            return new ToolResult(true, "web", "READ_WEB_PAGE", request.requestId(), request.conversationId(), false,
                                    List.of("web:page"), "Read page", Map.of(
                                    "url", request.arguments().get("url"),
                                    "title", "OLX RTX 3060 Ti",
                                    "content", "Oferta dotyczy karty graficznej RTX 3060 Ti. Sprzedajacy nie podal widocznej ceny.",
                                    "characters", 82
                            ), "", "", false, "");
                        }
                        return webResult(request, "RTX 3060 Ti Uzywana - Niska cena na Allegro",
                                "https://allegro.pl/oferta/rtx-3060-ti",
                                "RTX 3060 Ti uzywana cena 950 PLN");
                    }
                },
                webRegistry(),
                query -> ToolIntent.SEARCH_WEB,
                new ToolRuntimeProperties(true, 5, 5, 1, 30),
                new NoopCognitiveEventBus(),
                new ToolRuntimeDebugService(),
                new ObjectMapper()
        );

        ToolCallingResult result = runtime.execute(new ToolCallingRequest(
                "request-9",
                "conversation-1",
                "po ile sa uzywane 3060 ti?",
                "Retrieve the current market price for NVIDIA GeForce RTX 3060 Ti in Polish currency.",
                "Need current marketplace price.",
                "Base prompt",
                new Brain(BrainType.FAST, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.LOW),
                KnowledgeMode.FAST
        ));

        assertThat(result.handled()).isTrue();
        assertThat(executions).hasValue(3);
        assertThat(result.results().get(1).data()).containsEntry("pageQualityAccepted", false);
        assertThat(executed.get().operation()).isEqualTo("SEARCH_WEB");
        assertThat(String.valueOf(executed.get().arguments().get("query"))).containsIgnoringCase("RTX 3060 Ti");
    }

    @Test
    void continuesWebResearchWhenReadPageIsBlocked() {
        AtomicReference<ToolRequest> executed = new AtomicReference<>();
        AtomicInteger executions = new AtomicInteger();
        AtomicInteger modelCalls = new AtomicInteger();
        DefaultToolCallingRuntime runtime = new DefaultToolCallingRuntime(
                List.of(new SequentialStubProvider(modelCalls,
                        """
                        {"action":"TOOL_CALL","tool":"web","operation":"READ_WEB_PAGE","arguments":{"url":"https://olx.pl/oferta/rtx-5060-ti"},"reason":"Read the best marketplace result."}
                        """,
                        """
                        {"action":"TOOL_CALL","tool":"web","operation":"SEARCH_WEB","arguments":{"query":"RTX 5060 Ti cena uzywana Allegro","maxResults":5},"reason":"The previous page was blocked, search another source."}
                        """
                )),
                new StubToolManager(executed, executions) {
                    @Override
                    public ToolResult execute(ToolRequest request) {
                        executed.set(request);
                        int call = executions.incrementAndGet();
                        if (call == 1) {
                            return webResult(request, "rtx 5060 ti - Komputery w dobrej cenie - OLX",
                                    "https://olx.pl/oferta/rtx-5060-ti",
                                    "RTX 5060 Ti karta graficzna uzywana OLX");
                        }
                        if (call == 2) {
                            assertThat(request.operation()).isEqualTo("READ_WEB_PAGE");
                            return new ToolResult(false, "web", "READ_WEB_PAGE", request.requestId(), request.conversationId(), false,
                                    List.of(), "Web page read failed", Map.of(
                                    "url", request.arguments().get("url"),
                                    "statusCode", 403,
                                    "errorMessage", "HTTP 403"
                            ), "WEB_PAGE_READ_FAILED", "HTTP 403", false, "");
                        }
                        return webResult(request, "RTX 5060 Ti - Niska cena na Allegro",
                                "https://allegro.pl/oferta/rtx-5060-ti",
                                "RTX 5060 Ti uzywana cena 2500 PLN");
                    }
                },
                webRegistry(),
                query -> ToolIntent.SEARCH_WEB,
                new ToolRuntimeProperties(true, 5, 5, 1, 30),
                new NoopCognitiveEventBus(),
                new ToolRuntimeDebugService(),
                new ObjectMapper()
        );

        ToolCallingResult result = runtime.execute(new ToolCallingRequest(
                "request-10",
                "conversation-1",
                "po ile sa uzywane 5060 ti?",
                "Retrieve the current market price for NVIDIA GeForce RTX 5060 Ti in Polish currency.",
                "Need current marketplace price.",
                "Base prompt",
                new Brain(BrainType.FAST, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.LOW),
                KnowledgeMode.FAST
        ));

        assertThat(result.handled()).isTrue();
        assertThat(executions).hasValue(3);
        assertThat(executed.get().operation()).isEqualTo("SEARCH_WEB");
        assertThat(String.valueOf(executed.get().arguments().get("query"))).containsIgnoringCase("RTX 5060 Ti");
    }

    private ToolRegistry webRegistry() {
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

    private static final class StubProvider implements com.jarvis.common.ai.AIProvider {

        private final String response;
        private final AtomicInteger calls;

        private StubProvider(String response) {
            this(response, new AtomicInteger());
        }

        private StubProvider(String response, AtomicInteger calls) {
            this.response = response;
            this.calls = calls;
        }

        @Override
        public String provider() {
            return "stub";
        }

        @Override
        public ChatResponse chat(Brain brain, String prompt) {
            calls.incrementAndGet();
            return new ChatResponse(response);
        }

        @Override
        public ChatResponse chat(Brain brain, String prompt, AIJobType jobType) {
            calls.incrementAndGet();
            return new ChatResponse(response);
        }

        @Override
        public void stream(String conversationId, Brain brain, String prompt, ChatEventSink eventSink) {
        }
    }

    private static final class SequentialStubProvider implements com.jarvis.common.ai.AIProvider {

        private final AtomicInteger calls;
        private final List<String> responses;

        private SequentialStubProvider(AtomicInteger calls, String... responses) {
            this.calls = calls;
            this.responses = List.of(responses);
        }

        @Override
        public String provider() {
            return "stub";
        }

        @Override
        public ChatResponse chat(Brain brain, String prompt) {
            return next();
        }

        @Override
        public ChatResponse chat(Brain brain, String prompt, AIJobType jobType) {
            return next();
        }

        @Override
        public void stream(String conversationId, Brain brain, String prompt, ChatEventSink eventSink) {
        }

        private ChatResponse next() {
            int index = calls.getAndIncrement();
            String response = responses.get(Math.min(index, responses.size() - 1));
            return new ChatResponse(response);
        }
    }

    private static class StubToolManager implements ToolManager {

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
            String query = String.valueOf(request.arguments().get("query"));
            return webResult(request, query, "https://example.com/result", query + " cena 1299 PLN");
        }

        protected ToolResult webResult(ToolRequest request, String title, String url, String snippet) {
            String query = String.valueOf(request.arguments().get("query"));
            return new ToolResult(true, "web", "SEARCH_WEB", request.requestId(), request.conversationId(), false,
                    List.of("web:search"), "Found USD PLN rate", Map.of(
                    "query", query,
                    "results", List.of(Map.of(
                            "title", title,
                            "url", url,
                            "snippet", snippet,
                            "source", "Example"
                    ))
            ),
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
