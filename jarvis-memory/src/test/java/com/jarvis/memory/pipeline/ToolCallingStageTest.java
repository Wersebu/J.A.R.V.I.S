package com.jarvis.memory.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.common.ai.AIProvider;
import com.jarvis.common.ai.Brain;
import com.jarvis.common.ai.BrainType;
import com.jarvis.common.ai.ImageAttachment;
import com.jarvis.common.ai.ReasoningLevel;
import com.jarvis.common.dto.ChatRequest;
import com.jarvis.common.dto.ChatResponse;
import com.jarvis.common.event.ChatEventSink;
import com.jarvis.common.event.CognitiveEvent;
import com.jarvis.common.event.CognitiveEventBus;
import com.jarvis.common.event.CognitiveEventType;
import com.jarvis.common.event.GenerationFinishedEvent;
import com.jarvis.common.event.TokenEvent;
import com.jarvis.tools.ToolResult;
import com.jarvis.tools.dataset.StoreAuditDatasetService;
import com.jarvis.tools.runtime.ToolCallingRequest;
import com.jarvis.tools.runtime.ToolCallingResult;
import com.jarvis.tools.runtime.ToolRuntimeStep;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ToolCallingStageTest {

    @Test
    void responseBodyToolRequestIsForwardedToNativeToolLoopEvenWhenMetadataWasLost() {
        AtomicReference<ToolCallingRequest> captured = new AtomicReference<>();
        ToolCallingStage stage = new ToolCallingStage(request -> {
            captured.set(request);
            return new ToolCallingResult(true, "Foldery: Workspace, ReplicatedStorage", List.of(), List.of());
        }, List.of(), new MainModelActionParser(new ObjectMapper()), new StoreAuditDatasetService(new NoopCognitiveEventBus()));

        ChatRequest request = new ChatRequest("conversation-1",
                "Podaj liste folderow dostepnych w aktualnie polaczonym projekcie Roblox Studio.", Instant.now());
        String misplacedToolRequest = """
                {"type":"TOOL_REQUEST","goal":"List folders in the connected Roblox Studio project","reason":"Need Roblox MCP inspection","context":{"provider":"roblox"}}
                """;
        PipelineContext context = PipelineContext.initial("conversation-1", "request-1", request, event -> { }, event -> { })
                .withExecution(new com.jarvis.brain.decision.ExecutionPlan(
                                com.jarvis.brain.decision.TaskType.CONVERSATION, 1.0, 1, false, 0, 0,
                                BrainType.FAST, "stub-model", ReasoningLevel.LOW, "test"),
                        new Brain(BrainType.FAST, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.LOW))
                .withResponse(misplacedToolRequest, GenerationFinishedEvent.create("conversation-1", 0,
                        BrainType.FAST, "stub-model", null, 1, null));

        PipelineContext result = stage.execute(context);

        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().goal()).isEqualTo("List folders in the connected Roblox Studio project");
        assertThat(captured.get().reason()).isEqualTo("Need Roblox MCP inspection");
        assertThat(captured.get().context()).containsEntry("provider", "roblox");
        assertThat(result.response()).isEqualTo("Foldery: Workspace, ReplicatedStorage");
        assertThat(result.metadata()).containsEntry("toolCallingHandled", true);
    }

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

    // Regression for the exact reported bug: the model's private "thinking" contained a full
    // reasoning trace, but the tool loop's plain-text final turn was a prose preamble followed by
    // a TOOL_REQUEST-shaped {"type":"TOOL_REQUEST","goal":"..."} envelope written out of habit
    // after the loop had already ended - actionParser.parse() still succeeds (it strips prose
    // around a {...} block), so this must never fall through to displaying that raw text verbatim.
    @Test
    void streamToolFinalAnswerNeverLeaksRawJsonWhenTheLoopEndsOnAMisplacedToolRequestEnvelope() throws Exception {
        ToolCallingStage stage = new ToolCallingStage(request -> new ToolCallingResult(false, "", List.of(), List.of()),
                List.of(), new MainModelActionParser(new ObjectMapper()), new StoreAuditDatasetService(new NoopCognitiveEventBus()));

        ToolResult knowledgeRead = new ToolResult(true, "knowledge", "READ_DOCUMENT", "request-1", "conversation-1",
                false, List.of(), "Document read", Map.of("content", "..."), "", "", false, "");
        String misplacedToolRequest = "Odczytuje dane ze zdjec i przygotowuje liste sklepow.\n\n"
                + "{\"type\": \"TOOL_REQUEST\", \"goal\": \"Geocode the following extracted store addresses: "
                + "1. Biedronka, Korczaka 7, 08-400 Garwolin\", \"reason\": \"Need coordinates.\", "
                + "\"context\": {\"importantEntities\": []}}";
        ToolCallingResult loopResult = new ToolCallingResult(true, misplacedToolRequest, List.of(), List.of(knowledgeRead));

        ChatRequest request = new ChatRequest("conversation-1", "przygotuj grafik na sierpien", Instant.now());
        PipelineContext context = PipelineContext.initial("conversation-1", "request-1", request, event -> { }, event -> { });

        Method method = ToolCallingStage.class.getDeclaredMethod("streamToolFinalAnswer", PipelineContext.class, ToolCallingResult.class);
        method.setAccessible(true);
        String answer = (String) method.invoke(stage, context, loopResult);

        assertThat(answer).doesNotContain("\"type\"", "TOOL_REQUEST", "\"goal\"", "importantEntities");
        assertThat(answer).isEqualTo("Zakonczylem prace z narzedziami, ale nie otrzymalem czytelnej tresci koncowej odpowiedzi.");
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

    // Regression for the exact reported bug: the model's second native tool call in the loop was
    // malformed, got rejected as INVALID_TOOL_CALL (message literally "Invalid native tool call"),
    // the loop then ran out of its call budget with a blank finalAnswer, and that internal,
    // developer-facing error message leaked straight into the chat as if it were the assistant's
    // real answer.
    @Test
    void fallbackToolAnswerNeverSurfacesAFailedToolResultsInternalErrorMessage() throws Exception {
        ToolCallingStage stage = new ToolCallingStage(request -> new ToolCallingResult(false, "", List.of(), List.of()),
                List.of(), new MainModelActionParser(new ObjectMapper()), new StoreAuditDatasetService(new NoopCognitiveEventBus()));

        ToolResult readDocument = new ToolResult(true, "knowledge", "READ_DOCUMENT", "request-1", "conversation-1",
                false, List.of(), "Document read", Map.of("content", "..."), "", "", false, "");
        ToolResult invalidCall = new ToolResult(false, "location", "GEOCODE", "request-1", "conversation-1",
                false, List.of(), "Invalid native tool call", Map.of("error", "Unrecognized field \"records\""),
                "INVALID_TOOL_CALL", "Unrecognized field \"records\"", false, "");
        // Blank finalAnswer simulates the loop exiting after exhausting its call budget mid-task.
        ToolCallingResult loopResult = new ToolCallingResult(true, "", List.of(), List.of(readDocument, invalidCall));

        ChatRequest request = new ChatRequest("conversation-1", "przygotuj grafik na sierpien", Instant.now());
        PipelineContext context = PipelineContext.initial("conversation-1", "request-1", request, event -> { }, event -> { });

        Method method = ToolCallingStage.class.getDeclaredMethod("fallbackToolAnswer", PipelineContext.class, ToolCallingResult.class);
        method.setAccessible(true);
        String answer = (String) method.invoke(stage, context, loopResult);

        assertThat(answer).doesNotContain("Invalid native tool call");
        assertThat(answer).isEqualTo("Zakonczylem prace z narzedziami, ale model nie zwrocil tresci odpowiedzi.");
    }

    @Test
    void fallbackToolAnswerNeverSurfacesAKnowledgeToolStatusLabelAsARealAnswer() throws Exception {
        // Regression test: when the native tool loop ends without the model producing any real
        // text (e.g. it goes silent after reading a document) and every tool result so far is a
        // KnowledgeTool operation, the fallback must never pick up KnowledgeTool's generic
        // operation-status "message" (e.g. "Document read", "Search finished") and present it to
        // the user as if it were an actual answer.
        ToolCallingStage stage = new ToolCallingStage(request -> new ToolCallingResult(false, "", List.of(), List.of()),
                List.of(), new MainModelActionParser(new ObjectMapper()), new StoreAuditDatasetService(new NoopCognitiveEventBus()));

        ToolResult searchDocument = new ToolResult(true, "knowledge", "SEARCH_DOCUMENT", "request-1", "conversation-1",
                false, List.of(), "Search finished", Map.of(), "", "", false, "");
        ToolResult readDocument = new ToolResult(true, "knowledge", "READ_DOCUMENT", "request-1", "conversation-1",
                false, List.of(), "Document read", Map.of("content", "..."), "", "", false, "");
        // Blank finalAnswer simulates the native loop exiting via EMPTY_MODEL_RESPONSE_WITHOUT_TOOL_CALL.
        ToolCallingResult loopResult = new ToolCallingResult(true, "", List.of(), List.of(searchDocument, readDocument));

        ChatRequest request = new ChatRequest("conversation-1", "przygotuj grafik na sierpien", Instant.now());
        PipelineContext context = PipelineContext.initial("conversation-1", "request-1", request, event -> { }, event -> { });

        Method method = ToolCallingStage.class.getDeclaredMethod("fallbackToolAnswer", PipelineContext.class, ToolCallingResult.class);
        method.setAccessible(true);
        String answer = (String) method.invoke(stage, context, loopResult);

        assertThat(answer).isNotEqualTo("Document read");
        assertThat(answer).isNotEqualTo("Search finished");
        assertThat(answer).isEqualTo("Zakonczylem prace z narzedziami, ale model nie zwrocil tresci odpowiedzi.");
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

        // Long enough to exceed STRUCTURED_DETECTION_PROBE_CHARS on its own - this test only
        // exercises handleToolAnswerToken() directly (never finishToolAnswerStream(), which is what
        // flushes a still-undecided buffer at the real end of a stream), so the mode decision must
        // resolve to "plain text" within the fed tokens themselves for anything to be observed here.
        String plain = "`x` jest niezdefiniowana w tym zakresie kodu, wiec trzeba to poprawic przed dalsza analiza.";
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

    // TEST 1 (ToolCallingStage half): the pipeline's resolved images must reach the
    // ToolCallingRequest handed to the native tool runtime - the exact hop where they used to be
    // silently dropped.
    @Test
    void executeForwardsThePipelinesResolvedImagesIntoTheToolCallingRequest() {
        AtomicReference<com.jarvis.tools.runtime.ToolCallingRequest> captured = new AtomicReference<>();
        ToolCallingStage stage = new ToolCallingStage(request -> {
            captured.set(request);
            return new ToolCallingResult(true, "Final answer.", List.of(), List.of());
        }, List.of(), new MainModelActionParser(new ObjectMapper()), new StoreAuditDatasetService(new NoopCognitiveEventBus()));

        ImageAttachment photo = new ImageAttachment("base64data", "sklepy.jpg");
        ChatRequest request = new ChatRequest("conversation-1", "ustaw grafik na sierpien", Instant.now());
        PipelineContext context = PipelineContext.initial("conversation-1", "request-1", request, event -> { }, event -> { })
                .withExecution(null, new Brain(BrainType.FAST, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.LOW))
                .withImages(List.of(photo))
                .withMetadata("mainModelAction", "TOOL_REQUEST")
                .withMetadata("toolGoal", "READ Work/Scheduling/StoreAuditScheduleWorkflow.md")
                .withMetadata("toolReason", "Need the workflow procedure.");

        stage.execute(context);

        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().images()).extracting(ImageAttachment::originalFileName).containsExactly("sklepy.jpg");
    }

    @Test
    void executeForwardsAnEmptyImageListWhenTheCurrentMessageHasNoAttachments() {
        AtomicReference<com.jarvis.tools.runtime.ToolCallingRequest> captured = new AtomicReference<>();
        ToolCallingStage stage = new ToolCallingStage(request -> {
            captured.set(request);
            return new ToolCallingResult(true, "Final answer.", List.of(), List.of());
        }, List.of(), new MainModelActionParser(new ObjectMapper()), new StoreAuditDatasetService(new NoopCognitiveEventBus()));

        ChatRequest request = new ChatRequest("conversation-1", "sprawdz w wiedzy karte graficzna", Instant.now());
        PipelineContext context = PipelineContext.initial("conversation-1", "request-1", request, event -> { }, event -> { })
                .withExecution(null, new Brain(BrainType.FAST, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.LOW))
                .withMetadata("mainModelAction", "TOOL_REQUEST")
                .withMetadata("toolGoal", "Read the hardware document.")
                .withMetadata("toolReason", "test");

        stage.execute(context);

        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().images()).isEmpty();
    }

    // Regression for the second half of the reported bug: the native tool loop exhausted its
    // budget with no final text, ToolCallingStage's own tool-less "final synthesis" call ran, and
    // that call's answer was itself a {"type":"TOOL_REQUEST",...} envelope written as text - Core
    // must execute that request (re-enter the real tool runtime), not turn it into an apology.
    @Test
    void streamToolFinalAnswerReentersTheToolRuntimeWhenFinalSynthesisReturnsATextToolRequest() throws Exception {
        AtomicReference<ToolCallingRequest> reentryRequest = new AtomicReference<>();
        ToolCallingStage stage = new ToolCallingStage(
                request -> {
                    reentryRequest.set(request);
                    return new ToolCallingResult(true, "Real answer after reentry.", List.of(), List.of());
                },
                List.of(new StubToolRequestStreamingProvider()),
                new MainModelActionParser(new ObjectMapper()), new StoreAuditDatasetService(new NoopCognitiveEventBus()));

        ToolResult knowledgeRead = new ToolResult(true, "knowledge", "READ_DOCUMENT", "request-1", "conversation-1",
                false, List.of(), "Document read", Map.of("content", "..."), "", "", false, "");
        // Blank finalAnswer simulates the native loop exhausting its budget mid-task with tool
        // results but no final text - exactly when this tool-less synthesis call runs.
        ToolCallingResult loopResult = new ToolCallingResult(true, "", List.of(), List.of(knowledgeRead));

        ChatRequest request = new ChatRequest("conversation-1", "przygotuj harmonogram wizyt", Instant.now());
        PipelineContext context = PipelineContext.initial("conversation-1", "request-1", request, event -> { }, event -> { })
                .withExecution(null, new Brain(BrainType.FAST, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.LOW));

        Method method = ToolCallingStage.class.getDeclaredMethod("streamToolFinalAnswer", PipelineContext.class, ToolCallingResult.class);
        method.setAccessible(true);
        String answer = (String) method.invoke(stage, context, loopResult);

        assertThat(answer).isEqualTo("Real answer after reentry.");
        assertThat(reentryRequest.get()).isNotNull();
        assertThat(reentryRequest.get().goal()).isEqualTo("Geocode the extracted store addresses");
    }

    // The re-entry above must be bounded: if even the re-entered call keeps returning another
    // TOOL_REQUEST-as-text, this stage must eventually stop and fall back to the honest apology
    // rather than recursing forever.
    @Test
    void streamToolFinalAnswerStopsReenteringAfterTheBoundedRetryLimitAndFallsBackToAnHonestApology() throws Exception {
        ToolCallingStage stage = new ToolCallingStage(
                request -> new ToolCallingResult(true, "", List.of(), List.of()), // reentry also returns blank
                List.of(new StubToolRequestStreamingProvider()),
                new MainModelActionParser(new ObjectMapper()), new StoreAuditDatasetService(new NoopCognitiveEventBus()));

        ToolResult knowledgeRead = new ToolResult(true, "knowledge", "READ_DOCUMENT", "request-1", "conversation-1",
                false, List.of(), "Document read", Map.of("content", "..."), "", "", false, "");
        ToolCallingResult loopResult = new ToolCallingResult(true, "", List.of(), List.of(knowledgeRead));

        ChatRequest request = new ChatRequest("conversation-1", "przygotuj harmonogram wizyt", Instant.now());
        PipelineContext context = PipelineContext.initial("conversation-1", "request-1", request, event -> { }, event -> { })
                .withExecution(null, new Brain(BrainType.FAST, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.LOW));

        Method method = ToolCallingStage.class.getDeclaredMethod("streamToolFinalAnswer", PipelineContext.class, ToolCallingResult.class);
        method.setAccessible(true);
        String answer = (String) method.invoke(stage, context, loopResult);

        assertThat(answer).isEqualTo("Zakonczylem prace z narzedziami, ale nie otrzymalem czytelnej tresci koncowej odpowiedzi.");
    }

    // TEST 10: finalProtocolGuard - a raw TOOL_REQUEST envelope, with nothing around it, must never
    // reach the user - the last, unconditional guard before context.withResponse().
    @Test
    void finalProtocolGuardNeverLeaksARawToolRequestEnvelope() throws Exception {
        ToolCallingStage stage = new ToolCallingStage(request -> new ToolCallingResult(false, "", List.of(), List.of()),
                List.of(), new MainModelActionParser(new ObjectMapper()), new StoreAuditDatasetService(new NoopCognitiveEventBus()));

        String raw = "{\"type\": \"TOOL_REQUEST\", \"goal\": \"Create a canonical storeDataset\", "
                + "\"reason\": \"Need the dataset before scheduling.\", \"context\": {\"importantEntities\": []}}";
        ChatRequest request = new ChatRequest("conversation-1", "przygotuj grafik na sierpien", Instant.now());
        PipelineContext context = PipelineContext.initial("conversation-1", "request-1", request, event -> { }, event -> { });

        Method method = ToolCallingStage.class.getDeclaredMethod("finalProtocolGuard", PipelineContext.class, String.class);
        method.setAccessible(true);
        String guarded = (String) method.invoke(stage, context, raw);

        assertThat(guarded).doesNotContain("\"type\"", "TOOL_REQUEST", "\"goal\"", "importantEntities");
    }

    // TEST 11: the same envelope wrapped in a ```json fence must also never leak.
    @Test
    void finalProtocolGuardNeverLeaksAMarkdownFencedToolRequestEnvelope() throws Exception {
        ToolCallingStage stage = new ToolCallingStage(request -> new ToolCallingResult(false, "", List.of(), List.of()),
                List.of(), new MainModelActionParser(new ObjectMapper()), new StoreAuditDatasetService(new NoopCognitiveEventBus()));

        String fenced = "```json\n{\"type\": \"TOOL_REQUEST\", \"goal\": \"Create a canonical storeDataset\", "
                + "\"reason\": \"Need the dataset before scheduling.\"}\n```";
        ChatRequest request = new ChatRequest("conversation-1", "przygotuj grafik na sierpien", Instant.now());
        PipelineContext context = PipelineContext.initial("conversation-1", "request-1", request, event -> { }, event -> { });

        Method method = ToolCallingStage.class.getDeclaredMethod("finalProtocolGuard", PipelineContext.class, String.class);
        method.setAccessible(true);
        String guarded = (String) method.invoke(stage, context, fenced);

        assertThat(guarded).doesNotContain("\"type\"", "TOOL_REQUEST", "```");
    }

    // TEST 12: a prose preamble in front of the envelope must also never leak.
    @Test
    void finalProtocolGuardNeverLeaksAToolRequestEnvelopePrecededByProse() throws Exception {
        ToolCallingStage stage = new ToolCallingStage(request -> new ToolCallingResult(false, "", List.of(), List.of()),
                List.of(), new MainModelActionParser(new ObjectMapper()), new StoreAuditDatasetService(new NoopCognitiveEventBus()));

        String prefixed = "Przygotowuje teraz kolejne wywolanie narzedzia.\n\n"
                + "{\"type\": \"TOOL_REQUEST\", \"goal\": \"Create a canonical storeDataset\", "
                + "\"reason\": \"Need the dataset before scheduling.\"}";
        ChatRequest request = new ChatRequest("conversation-1", "przygotuj grafik na sierpien", Instant.now());
        PipelineContext context = PipelineContext.initial("conversation-1", "request-1", request, event -> { }, event -> { });

        Method method = ToolCallingStage.class.getDeclaredMethod("finalProtocolGuard", PipelineContext.class, String.class);
        method.setAccessible(true);
        String guarded = (String) method.invoke(stage, context, prefixed);

        assertThat(guarded).doesNotContain("\"type\"", "TOOL_REQUEST");
    }

    // Companion to TEST 10-12: a genuine FINAL_ANSWER envelope must still be unwrapped to its
    // plain-text answer, not treated as a leak - the guard must not become overly aggressive.
    @Test
    void finalProtocolGuardUnwrapsAGenuineFinalAnswerEnvelope() throws Exception {
        ToolCallingStage stage = new ToolCallingStage(request -> new ToolCallingResult(false, "", List.of(), List.of()),
                List.of(), new MainModelActionParser(new ObjectMapper()), new StoreAuditDatasetService(new NoopCognitiveEventBus()));

        String structured = "{\"type\":\"FINAL_ANSWER\",\"answer\":\"Grafik jest gotowy.\"}";
        ChatRequest request = new ChatRequest("conversation-1", "przygotuj grafik na sierpien", Instant.now());
        PipelineContext context = PipelineContext.initial("conversation-1", "request-1", request, event -> { }, event -> { });

        Method method = ToolCallingStage.class.getDeclaredMethod("finalProtocolGuard", PipelineContext.class, String.class);
        method.setAccessible(true);
        String guarded = (String) method.invoke(stage, context, structured);

        assertThat(guarded).isEqualTo("Grafik jest gotowy.");
    }

    // Companion: genuine plain text (no envelope at all) must pass through unchanged.
    @Test
    void finalProtocolGuardLeavesGenuinePlainTextUnchanged() throws Exception {
        ToolCallingStage stage = new ToolCallingStage(request -> new ToolCallingResult(false, "", List.of(), List.of()),
                List.of(), new MainModelActionParser(new ObjectMapper()), new StoreAuditDatasetService(new NoopCognitiveEventBus()));

        String plain = "Grafik jest gotowy, wszystkie 23 sklepy zaplanowane.";
        ChatRequest request = new ChatRequest("conversation-1", "przygotuj grafik na sierpien", Instant.now());
        PipelineContext context = PipelineContext.initial("conversation-1", "request-1", request, event -> { }, event -> { });

        Method method = ToolCallingStage.class.getDeclaredMethod("finalProtocolGuard", PipelineContext.class, String.class);
        method.setAccessible(true);
        String guarded = (String) method.invoke(stage, context, plain);

        assertThat(guarded).isEqualTo(plain);
    }

    // handleToolAnswerToken: a structured envelope arriving after a short prose preamble, streamed
    // character-by-character exactly like a live model turn, must also never leak the raw JSON -
    // the confirmed root cause of the production TOOL_REQUEST leak (the old detector only checked
    // whether the very first character was '{').
    @Test
    void handleToolAnswerTokenUnwrapsAStructuredEnvelopeArrivingAfterAProsePreamble() throws Exception {
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

        String prefixed = "Ok: {\"type\":\"FINAL_ANSWER\",\"answer\":\"Rozpoczynam proces tworzenia grafiku.\"}";
        for (int index = 0; index < prefixed.length(); index++) {
            handleToken.invoke(stage, context, String.valueOf(prefixed.charAt(index)), streamState);
        }

        String streamed = String.join("", answerChunks);
        assertThat(streamed).isEqualTo("Rozpoczynam proces tworzenia grafiku.");
        assertThat(streamed).doesNotContain("\"type\"", "FINAL_ANSWER", "Ok:");
    }

    /**
     * Streams a single-token {@code {"type":"TOOL_REQUEST",...}} envelope as the whole model
     * answer, exactly like a model that wrote its next intended tool call as text instead of
     * making a real native tool call.
     */
    private static final class StubToolRequestStreamingProvider implements AIProvider {

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
            String json = "{\"type\":\"TOOL_REQUEST\",\"goal\":\"Geocode the extracted store addresses\","
                    + "\"reason\":\"Need coordinates.\"}";
            eventSink.publish(TokenEvent.create(conversationId, json));
            eventSink.publish(GenerationFinishedEvent.create(conversationId, 0, BrainType.FAST, "stub-model", 0, 0, 0.0d));
        }
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
