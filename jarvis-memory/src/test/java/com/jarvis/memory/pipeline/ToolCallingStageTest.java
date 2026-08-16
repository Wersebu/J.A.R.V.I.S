package com.jarvis.memory.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.common.dto.ChatRequest;
import com.jarvis.tools.ToolResult;
import com.jarvis.tools.runtime.ToolCallingResult;
import com.jarvis.tools.runtime.ToolRuntimeStep;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolCallingStageTest {

    @Test
    void streamToolFinalAnswerTrustsNativeLoopsOwnAnswerWithoutRedundantSynthesisCall() throws Exception {
        // Zero AIProviders on purpose: if the fix regresses and the stage falls through to the
        // separate synthesis call, selectProvider() throws because no provider is configured.
        ToolCallingStage stage = new ToolCallingStage(request -> new ToolCallingResult(false, "", List.of(), List.of()),
                List.of(), new MainModelActionParser(new ObjectMapper()));

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
                List.of(), new MainModelActionParser(new ObjectMapper()));

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
                List.of(), new MainModelActionParser(new ObjectMapper()));

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
    void fallbackWebSearchAnswerExtractsPolishZlotyPrice() throws Exception {
        ToolCallingStage stage = new ToolCallingStage(request -> new ToolCallingResult(false, "", List.of(), List.of()),
                List.of(), new MainModelActionParser(new ObjectMapper()));
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
                List.of(), new MainModelActionParser(new ObjectMapper()));
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
}
