package com.jarvis.memory.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.common.ai.BrainType;
import com.jarvis.common.dto.ChatRequest;
import com.jarvis.common.event.CognitiveEvent;
import com.jarvis.common.event.CognitiveEventBus;
import com.jarvis.common.event.CognitiveEventType;
import com.jarvis.tools.ToolResult;
import com.jarvis.tools.dataset.StoreAuditDatasetService;
import com.jarvis.tools.runtime.ToolCallingResult;
import com.jarvis.tools.runtime.ToolRuntimeStep;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolCallingStageTest {

    @Test
    void streamToolFinalAnswerTrustsNativeLoopsOwnAnswerWithoutRedundantSynthesisCall() throws Exception {
        // Zero AIProviders on purpose: if the fix regresses and the stage falls through to the
        // separate synthesis call, selectProvider() throws because no provider is configured.
        ToolCallingStage stage = new ToolCallingStage(request -> new ToolCallingResult(false, "", List.of(), List.of()),
                List.of(), new MainModelActionParser(new ObjectMapper()), new StoreAuditDatasetService(new NoopCognitiveEventBus()));

        ToolResult knowledgeRead = new ToolResult(true, "knowledge", "READ_DOCUMENT", "request-1", "conversation-1",
                false, List.of(), "Document read", Map.of("path", "hardware/graphics_card.txt", "content", "RTX 4060 Ti 16 GB"),
                "", "", false, "");
        ToolCallingResult loopResult = new ToolCallingResult(true,
                "Serwer ma RTX 4060 Ti 16 GB, aktualna cena ok. 2000 PLN.",
                List.of(new ToolRuntimeStep(1, "TOOL_CALL", "knowledge", "READ_DOCUMENT", "OK", knowledgeRead)),
                List.of(knowledgeRead));

        ChatRequest request = new ChatRequest("conversation-1", "Jaka karta graficzna jest w serwerze?", Instant.now());
        PipelineContext context = PipelineContext.initial("conversation-1", "request-1", request, event -> { }, event -> { });

        Method method = ToolCallingStage.class.getDeclaredMethod("streamToolFinalAnswer", PipelineContext.class, ToolCallingResult.class);
        method.setAccessible(true);
        String answer = (String) method.invoke(stage, context, loopResult);

        assertThat(answer).isEqualTo("Serwer ma RTX 4060 Ti 16 GB, aktualna cena ok. 2000 PLN.");
    }

    @Test
    void streamToolFinalAnswerPrefersModelFinalAnswerOverMarketplaceFailureTemplateWhenNoListingsVerified() throws Exception {
        ToolCallingStage stage = new ToolCallingStage(request -> new ToolCallingResult(false, "", List.of(), List.of()),
                List.of(), new MainModelActionParser(new ObjectMapper()), new StoreAuditDatasetService(new NoopCognitiveEventBus()));

        ToolResult failedMarketplaceSearch = new ToolResult(true, "web", "SEARCH_MARKETPLACE", "request-1", "conversation-1",
                false, List.of(), "Marketplace search finished",
                Map.of("marketplaceResearch", true, "marketplaceListings", List.of()), "", "", false, "");
        ToolResult geocodeSearch = new ToolResult(true, "web", "SEARCH_WEB", "request-1", "conversation-1",
                false, List.of(), "Web search finished", Map.of("results", List.of()), "", "", false, "");
        ToolCallingResult loopResult = new ToolCallingResult(true,
                "Nie znalazlem sklepow do sprzedazy, ale oto zoptymalizowana trasa dla podanych adresow: A -> B -> C.",
                List.of(), List.of(failedMarketplaceSearch, geocodeSearch));

        ChatRequest request = new ChatRequest("conversation-1", "Zaplanuj trase.", Instant.now());
        PipelineContext context = PipelineContext.initial("conversation-1", "request-1", request, event -> { }, event -> { });

        Method method = ToolCallingStage.class.getDeclaredMethod("streamToolFinalAnswer", PipelineContext.class, ToolCallingResult.class);
        method.setAccessible(true);
        String answer = (String) method.invoke(stage, context, loopResult);

        assertThat(answer).contains("zoptymalizowana trasa");
        assertThat(answer).doesNotContain("Nie udalo mi sie zweryfikowac aktualnych ofert");
    }

    @Test
    void streamToolFinalAnswerStillReturnsMarketplaceFailureTemplateWhenNoOtherAnswerExists() throws Exception {
        ToolCallingStage stage = new ToolCallingStage(request -> new ToolCallingResult(false, "", List.of(), List.of()),
                List.of(), new MainModelActionParser(new ObjectMapper()), new StoreAuditDatasetService(new NoopCognitiveEventBus()));

        ToolResult failedMarketplaceSearch = new ToolResult(true, "web", "SEARCH_MARKETPLACE", "request-2", "conversation-1",
                false, List.of(), "Marketplace search finished",
                Map.of("marketplaceResearch", true, "marketplaceListings", List.of()), "", "", false, "");
        ToolCallingResult loopResult = new ToolCallingResult(true, "", List.of(), List.of(failedMarketplaceSearch));

        ChatRequest request = new ChatRequest("conversation-1", "Znajdz uzywany RTX 4060 Ti.", Instant.now());
        PipelineContext context = PipelineContext.initial("conversation-1", "request-2", request, event -> { }, event -> { });

        Method method = ToolCallingStage.class.getDeclaredMethod("streamToolFinalAnswer", PipelineContext.class, ToolCallingResult.class);
        method.setAccessible(true);
        String answer = (String) method.invoke(stage, context, loopResult);

        assertThat(answer).isEqualTo("Nie udalo mi sie zweryfikowac aktualnych ofert spelniajacych te kryteria.");
    }

    @Test
    void streamToolFinalAnswerUnwrapsAStructuredJsonEnvelopeFromTheNativeLoopsOwnAnswer() throws Exception {
        ToolCallingStage stage = new ToolCallingStage(request -> new ToolCallingResult(false, "", List.of(), List.of()),
                List.of(), new MainModelActionParser(new ObjectMapper()), new StoreAuditDatasetService(new NoopCognitiveEventBus()));

        ToolResult webSearch = new ToolResult(true, "web", "SEARCH_WEB", "request-1", "conversation-1",
                false, List.of(), "Web search finished", Map.of("results", List.of()), "", "", false, "");
        String structuredAnswer = "{\"type\":\"FINAL_ANSWER\",\"answer\":\"RTX 4060 Ti ma 8 GB VRAM.\"}";
        ToolCallingResult loopResult = new ToolCallingResult(true, structuredAnswer, List.of(), List.of(webSearch));

        ChatRequest request = new ChatRequest("conversation-1", "Sprawdz informacje o RTX 4060 Ti.", Instant.now());
        PipelineContext context = PipelineContext.initial("conversation-1", "request-1", request, event -> { }, event -> { });

        Method method = ToolCallingStage.class.getDeclaredMethod("streamToolFinalAnswer", PipelineContext.class, ToolCallingResult.class);
        method.setAccessible(true);
        String answer = (String) method.invoke(stage, context, loopResult);

        assertThat(answer).isEqualTo("RTX 4060 Ti ma 8 GB VRAM.");
        assertThat(answer).doesNotContain("\"type\"", "\"answer\"", "FINAL_ANSWER");
    }

    @Test
    void streamToolFinalAnswerLeavesAGenuinePlainTextAnswerUnchanged() throws Exception {
        ToolCallingStage stage = new ToolCallingStage(request -> new ToolCallingResult(false, "", List.of(), List.of()),
                List.of(), new MainModelActionParser(new ObjectMapper()), new StoreAuditDatasetService(new NoopCognitiveEventBus()));

        ToolResult webSearch = new ToolResult(true, "web", "SEARCH_WEB", "request-1", "conversation-1",
                false, List.of(), "Web search finished", Map.of("results", List.of()), "", "", false, "");
        String plainAnswer = "RTX 4060 Ti ma 8 GB VRAM i architekture Ada Lovelace.";
        ToolCallingResult loopResult = new ToolCallingResult(true, plainAnswer, List.of(), List.of(webSearch));

        ChatRequest request = new ChatRequest("conversation-1", "Sprawdz informacje o RTX 4060 Ti.", Instant.now());
        PipelineContext context = PipelineContext.initial("conversation-1", "request-1", request, event -> { }, event -> { });

        Method method = ToolCallingStage.class.getDeclaredMethod("streamToolFinalAnswer", PipelineContext.class, ToolCallingResult.class);
        method.setAccessible(true);
        String answer = (String) method.invoke(stage, context, loopResult);

        assertThat(answer).isEqualTo(plainAnswer);
    }

    @Test
    void handleToolAnswerTokenUnwrapsAMarkdownFencedStructuredEnvelopeStreamedCharacterByCharacter() throws Exception {
        // Regression test: models sometimes wrap the FINAL_ANSWER JSON envelope in a ```json
        // fence despite being told to return raw JSON. Before this fix, the streaming detector
        // only checked whether the very first character was '{', so a fenced envelope was
        // classified as "plain text" and streamed to the user verbatim - the raw JSON (and the
        // fence around it) leaked straight into the chat as a rendered code block instead of the
        // actual answer text.
        ToolCallingStage stage = new ToolCallingStage(request -> new ToolCallingResult(false, "", List.of(), List.of()),
                List.of(), new MainModelActionParser(new ObjectMapper()), new StoreAuditDatasetService(new NoopCognitiveEventBus()));

        List<String> answerChunks = new ArrayList<>();
        ChatRequest request = new ChatRequest("conversation-1", "Utworz grafik audytow.", Instant.now());
        PipelineContext context = PipelineContext.initial("conversation-1", "request-1", request, event -> { },
                event -> {
                    if (event.event() == CognitiveEventType.ANSWER_TOKEN) {
                        answerChunks.add(event.message());
                    }
                });

        Class<?> streamStateClass = Class.forName("com.jarvis.memory.pipeline.ToolCallingStage$ToolAnswerStreamState");
        Constructor<?> streamStateConstructor = streamStateClass.getDeclaredConstructor();
        streamStateConstructor.setAccessible(true);
        Object streamState = streamStateConstructor.newInstance();

        Method handleToken = ToolCallingStage.class.getDeclaredMethod("handleToolAnswerToken",
                PipelineContext.class, String.class, streamStateClass);
        handleToken.setAccessible(true);

        String fenced = "```json\n{\"type\":\"FINAL_ANSWER\",\"answer\":\"Rozpoczynam proces tworzenia grafiku.\"}\n```";
        for (int index = 0; index < fenced.length(); index++) {
            handleToken.invoke(stage, context, String.valueOf(fenced.charAt(index)), streamState);
        }

        String streamed = String.join("", answerChunks);
        assertThat(streamed).isEqualTo("Rozpoczynam proces tworzenia grafiku.");
        assertThat(streamed).doesNotContain("```", "\"type\"", "FINAL_ANSWER");
    }

    @Test
    void handleToolAnswerTokenLeavesGenuinePlainTextStartingWithBacktickUnchanged() throws Exception {
        ToolCallingStage stage = new ToolCallingStage(request -> new ToolCallingResult(false, "", List.of(), List.of()),
                List.of(), new MainModelActionParser(new ObjectMapper()), new StoreAuditDatasetService(new NoopCognitiveEventBus()));

        List<String> answerChunks = new ArrayList<>();
        ChatRequest request = new ChatRequest("conversation-1", "Co to jest zmienna x?", Instant.now());
        PipelineContext context = PipelineContext.initial("conversation-1", "request-1", request, event -> { },
                event -> {
                    if (event.event() == CognitiveEventType.ANSWER_TOKEN) {
                        answerChunks.add(event.message());
                    }
                });

        Class<?> streamStateClass = Class.forName("com.jarvis.memory.pipeline.ToolCallingStage$ToolAnswerStreamState");
        Constructor<?> streamStateConstructor = streamStateClass.getDeclaredConstructor();
        streamStateConstructor.setAccessible(true);
        Object streamState = streamStateConstructor.newInstance();

        Method handleToken = ToolCallingStage.class.getDeclaredMethod("handleToolAnswerToken",
                PipelineContext.class, String.class, streamStateClass);
        handleToken.setAccessible(true);

        String plain = "`x` jest niezdefiniowana.";
        for (int index = 0; index < plain.length(); index++) {
            handleToken.invoke(stage, context, String.valueOf(plain.charAt(index)), streamState);
        }

        assertThat(String.join("", answerChunks)).isEqualTo(plain);
    }

    @Test
    void fallbackWebSearchAnswerExtractsPolishZlotyPrice() throws Exception {
        ToolCallingStage stage = new ToolCallingStage(request -> new ToolCallingResult(false, "", List.of(), List.of()),
                List.of(), new MainModelActionParser(new ObjectMapper()), new StoreAuditDatasetService(new NoopCognitiveEventBus()));
        ToolResult result = new ToolResult(true, "web", "SEARCH_WEB", "request-test", "conversation-test",
                false, List.of(), "Web search finished", Map.of(
                "acceptedResults", List.of(Map.of(
                        "title", "Karty graficzne NVIDIA GeForce RTX 5060 Ti - Sklep komputerowy",
                        "snippet", "Układ: GeForce RTX 5060 Ti; Pamięć: 16 GB; Cena: 2 999,00 zł.",
                        "url", "https://example.com/rtx-5060-ti"
                ))
        ), "", "", false, "");

        Method method = ToolCallingStage.class.getDeclaredMethod("fallbackWebSearchAnswer", ToolResult.class, boolean.class);
        method.setAccessible(true);

        String answer = (String) method.invoke(stage, result, false);

        assertThat(answer).contains("2 999,00 zł");
        assertThat(answer).doesNotContain("Web search finished");
    }

    @Test
    void fallbackWebSearchAnswerReturnsUrlWhenUserAskedForLink() throws Exception {
        ToolCallingStage stage = new ToolCallingStage(request -> new ToolCallingResult(false, "", List.of(), List.of()),
                List.of(), new MainModelActionParser(new ObjectMapper()), new StoreAuditDatasetService(new NoopCognitiveEventBus()));
        ToolResult result = new ToolResult(true, "web", "SEARCH_WEB", "request-test", "conversation-test",
                false, List.of(), "Web search finished", Map.of(
                "acceptedResults", List.of(Map.of(
                        "title", "RTX 4060 Ti OLX",
                        "snippet", "Cena: 1 050 zl.",
                        "url", "https://www.olx.pl/d/oferta/rtx-4060-ti"
                ))
        ), "", "", false, "");

        Method method = ToolCallingStage.class.getDeclaredMethod("fallbackWebSearchAnswer", ToolResult.class, boolean.class);
        method.setAccessible(true);

        String answer = (String) method.invoke(stage, result, true);

        assertThat(answer).contains("https://www.olx.pl/d/oferta/rtx-4060-ti");
        assertThat(answer).doesNotContain("1 050");
        assertThat(answer).doesNotContain("Web search finished");
    }

    private static final class NoopCognitiveEventBus implements CognitiveEventBus {

        @Override
        public void startRequest(String requestId, String conversationId, java.util.function.Consumer<CognitiveEvent> sink) {
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
