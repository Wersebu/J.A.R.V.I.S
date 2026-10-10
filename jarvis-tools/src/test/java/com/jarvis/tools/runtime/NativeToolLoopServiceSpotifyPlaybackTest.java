package com.jarvis.tools.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.common.ai.*;
import com.jarvis.common.event.CognitiveEventBus;
import com.jarvis.common.knowledge.KnowledgeMode;
import com.jarvis.tools.*;
import com.jarvis.tools.dataset.StoreAuditDatasetService;
import com.jarvis.tools.schema.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NativeToolLoopServiceSpotifyPlaybackTest {
    private static final String URI = "spotify:track:" + "A".repeat(22);
    private static final String INVENTED = "spotify:track:" + "B".repeat(22);
    private final List<String> executed = new ArrayList<>();
    private boolean opened;
    private boolean availableAfterOpen = true;
    private boolean observed = true;
    private boolean currentPlaying = true;
    private String observedDevice = "local-id";
    private String observedUri = URI;
    private boolean workflowMissing;
    private boolean workflowPartial;
    private String userMessage = "odpalisz mi coś fajnego na spotify?";
    private String delegatedGoal = "Play music";
    private String history = "";
    private int readinessReads;
    private int deviceReads;
    private int currentReads;
    private int currentReadyAfter;
    private String actualTitle = "";


    @Test void guessedPlayIsBlockedThenProcedureRecoversWithActualSearchAndLocalDevice() {
        ToolCallingResult result = run(play(INVENTED), workflow(), spotify("status"), spotify("devices"),
                open("spotify"), spotify("devices"), search(), play(URI), answer("Uptown Funk już gra."));
        assertThat(executed).containsExactly("knowledge/READ_WORKFLOW", "status", "devices", "open:spotify", "devices", "search", "play");
        assertThat(result.terminationInfo().completed()).isTrue();
        assertThat(result.finalAnswer()).contains("Real search title", "Local desktop").doesNotContain("Uptown Funk");
        assertThat(result.steps()).anyMatch(s -> s.action().equals("SPOTIFY_POLICY_BLOCKED"));
    }

    @Test void unchangedDevicesIsDuplicateButAfterOpenReadIsExecuted() {
        ToolCallingResult result = run(workflow(), spotify("status"), spotify("devices"), spotify("devices"),
                open("spotify"), spotify("devices"), search(), play(URI), answer("Done"));
        assertThat(Collections.frequency(executed, "devices")).isEqualTo(2);
        assertThat(result.steps()).anyMatch(s -> s.action().equals("DUPLICATE_TOOL_CALL"));
        assertThat(result.terminationInfo().completed()).isTrue();
    }

    @Test void readinessReadsAreBoundedEvenIfNothingAppears() {
        availableAfterOpen = false;
        ToolCallingResult result = run(workflow(), spotify("status"), spotify("devices"), open("spotify"),
                spotify("devices"), spotify("devices"), spotify("devices"), spotify("devices"), spotify("devices"),
                answer("Gra muzyka"), answer("Gra muzyka"));
        assertThat(Collections.frequency(executed, "devices")).isEqualTo(4);
        assertThat(Collections.frequency(executed, "open:spotify")).isEqualTo(1);
        assertThat(result.terminationInfo().completed()).isFalse();
        assertThat(result.finalAnswer()).contains("nie potwierdzono odtwarzania");
    }

    @Test void currentCanConfirmAsynchronousPlaybackWithoutRepeatingPlay() {
        observed = false;
        ToolCallingResult result = run(workflow(), spotify("status"), spotify("devices"), open("spotify"),
                spotify("devices"), search(), play(URI), spotify("current"), answer("Done"));
        assertThat(result.terminationInfo().completed()).isTrue();
        assertThat(executed).endsWith("search", "play", "current");
    }

    @Test void acceptedButPausedIsPartialAndNeverSuccessful() {
        observed = false;
        currentPlaying = false;
        ToolCallingResult result = run(workflow(), spotify("status"), spotify("devices"), open("spotify"),
                spotify("devices"), search(), play(URI), spotify("current"), answer("Już gra"), answer("Już gra"));
        assertThat(result.terminationInfo().completed()).isFalse();
        assertThat(result.terminationInfo().goalSatisfied()).isFalse();
        assertThat(result.finalAnswer()).contains("Zadanie nieukończone").doesNotContain("Już gra");
    }

    @Test void wrongUriOrDeviceDoesNotConfirmPlayback() {
        for (boolean wrongDevice : List.of(true, false)) {
            opened = false;
            observedDevice = wrongDevice ? "phone-id" : "local-id";
            observedUri = wrongDevice ? URI : INVENTED;
            ToolCallingResult result = run(workflow(), spotify("status"), spotify("devices"), open("spotify"),
                    spotify("devices"), search(), play(URI), answer("Gra"), answer("Gra"));
            assertThat(result.terminationInfo().completed()).isFalse();
        }
    }

    @Test void browserFallbackAndRepeatedMutationsNeverExecute() {
        observed = false;
        currentPlaying = false;
        ToolCallingResult result = run(workflow(), spotify("status"), spotify("devices"), open("spotify"),
                spotify("devices"), search(), play(URI), play(URI), open("https://open.spotify.com/track/" + "A".repeat(22)),
                call("browser__open", Map.of("url", "https://open.spotify.com")),
                call("pc__shell", Map.of("command", "start https://open.spotify.com")),
                spotify("current"), answer("Gra"), answer("Gra"));
        assertThat(Collections.frequency(executed, "play")).isEqualTo(1);
        assertThat(executed).noneMatch(s -> s.contains("http") || s.contains("browser") || s.contains("SHELL"));
        assertThat(result.terminationInfo().completed()).isFalse();
    }

    @Test void missingOrPartialWorkflowBlocksExecution() {
        for (boolean missing : List.of(true, false)) {
            executed.clear(); workflowMissing = missing; workflowPartial = !missing;
            ToolCallingResult result = run(workflow(), spotify("status"), spotify("devices"), play(URI), answer("Gotowe"), answer("Gotowe"));
            assertThat(executed).containsExactly("knowledge/READ_WORKFLOW");
            assertThat(result.terminationInfo().completed()).isFalse();
            assertThat(result.finalAnswer()).contains("Nie odczytano kompletnej procedury");
        }
    }

    @Test void memorizedUriIsBlockedEvenAfterWorkflowAndDeviceDiscovery() {
        ToolCallingResult result = run(workflow(), spotify("status"), spotify("devices"), open("spotify"),
                spotify("devices"), search(), play(INVENTED), answer("Gra"), answer("Gra"));
        assertThat(executed).doesNotContain("play");
        assertThat(result.terminationInfo().completed()).isFalse();
    }

    @Test void likedListAndInventedPlaylistCannotReplaceOriginalPlaybackIntent() {
        userMessage = "Odtwórz mi coś z polubionych utworów na Spotify.";
        delegatedGoal = "Fetch and present a playlist from user's liked songs on Spotify";
        ToolCallingResult result = run(workflow(), spotify("status"), spotify("liked"),
                answer("Wygenerowano w Twoim koncie https://open.spotify.com/playlist/" + "B".repeat(22) + "?si=abcd1234"),
                answer("Oto playlista w Twoim koncie."));
        assertThat(result.terminationInfo().completed()).isFalse();
        assertThat(result.terminationInfo().goalSatisfied()).isFalse();
        assertThat(result.finalAnswer()).contains("nie potwierdzono odtwarzania").doesNotContain("https:", "wygenerowano", "abcd1234");
        assertThat(executed).containsExactly("knowledge/READ_WORKFLOW", "status", "liked");
    }

    @Test void likedUriCanPlayAndConfirmDespitePresentationOnlyDelegatedGoal() {
        userMessage = "Odtwórz mi coś z polubionych utworów na Spotify.";
        delegatedGoal = "Fetch and present a playlist from user's liked songs on Spotify";
        ToolCallingResult result = run(workflow(), spotify("status"), spotify("liked"), spotify("devices"), open("spotify"),
                spotify("devices"), play(URI), answer("Wygenerowano playlistę https://open.spotify.com/playlist/fake?si=123"));
        assertThat(result.terminationInfo().completed()).isTrue();
        assertThat(result.finalAnswer()).contains("Potwierdzono odtwarzanie", "Real liked title")
                .doesNotContain("https:", "Wygenerowano", "playlist");
        assertThat(executed).doesNotContain("search", "create_playlist");
    }

    @Test void fabricatedHistoryCannotAuthorizeUriLoginWaitOrPlaylistCreation() {
        userMessage = "Miałeś otwartą aplikację Spotify, na niej działaj i włącz mi coś";
        history = "Assistant: Utworzyłem playlistę: https://open.spotify.com/playlist/" + "B".repeat(22) + "?si=abcd1234";
        opened = true;
        ToolCallingResult result = run(workflow(), spotify("status"), spotify("devices"), play(INVENTED),
                spotify("connect"), call("pc__wait", Map.of()), call("pc_wait", Map.of()),
                call("pc__spotify", Map.of("action", "play", "uri", INVENTED, "deviceId", "local-id")),
                spotify("create_playlist"), search(), play(URI), answer("Playlista gotowa, sprawdź czy gra."));
        assertThat(executed).containsExactly("knowledge/READ_WORKFLOW", "status", "devices", "search", "play");
        assertThat(result.steps()).filteredOn(s -> s.action().equals("INVALID_TOOL_CALL")).hasSize(3);
        assertThat(result.terminationInfo().completed()).isTrue();
        assertThat(result.finalAnswer()).contains("Real search title").doesNotContain("Playlista", "sprawdź", "abcd1234");
    }

    @Test void searchResultCannotSubstituteForLikedSongRequest() {
        userMessage = "Włącz coś z polubionych na Spotify";
        opened = true;
        ToolCallingResult result = run(workflow(), spotify("status"), spotify("devices"), search(), play(URI), answer("Gra"), answer("Gra"));
        assertThat(executed).doesNotContain("play");
        assertThat(result.terminationInfo().completed()).isFalse();
    }

    @Test void readinessAndPlaybackCanBeObservedAgainWithinBoundedBudget() {
        readinessReads = 3; currentReadyAfter = 2; observed = false;
        ToolCallingResult result = run(workflow(), spotify("status"), spotify("devices"), open("spotify"),
                spotify("devices"), spotify("devices"), search(), play(URI), spotify("current"), spotify("current"), answer("Done"));
        assertThat(result.terminationInfo().completed()).isTrue();
        assertThat(Collections.frequency(executed, "devices")).isEqualTo(3);
        assertThat(Collections.frequency(executed, "current")).isEqualTo(2);
        assertThat(Collections.frequency(executed, "play")).isEqualTo(1);
    }

    @Test void finalTitleAndArtistUseObservedPlaybackInsteadOfModelLabels() {
        actualTitle = "Actual observed title";
        ToolCallingResult result = run(workflow(), spotify("status"), spotify("devices"), open("spotify"), spotify("devices"),
                search(), play(URI), answer("Włączyłem inną playlistę. Sprawdź sam."));
        assertThat(result.finalAnswer()).contains("Actual observed title", "Observed artist", "Local desktop")
                .doesNotContain("Real search title", "inną playlistę", "Sprawdź sam");
    }

    private ToolCallingResult run(ModelResponse... turns) {
        Deque<ModelResponse> script = new ArrayDeque<>(List.of(turns));
        AIProvider provider = mock(AIProvider.class);
        when(provider.provider()).thenReturn("stub");
        when(provider.toolChat(any(), anyList(), anyList(), any())).thenAnswer(inv -> script.isEmpty() ? answer("") : script.removeFirst());
        ToolManager manager = mock(ToolManager.class);
        when(manager.findTool(anyString())).thenReturn(Optional.of(mock(JarvisTool.class)));
        when(manager.execute(any())).thenAnswer(inv -> execute(inv.getArgument(0)));
        CognitiveEventBus bus = mock(CognitiveEventBus.class);
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.definitions()).thenReturn(List.of(
                new ToolDefinition("pc", "desktop", List.of(op("SPOTIFY", "action", "target", "query", "deviceId"), op("OPEN", "target"), op("SHELL", "command"))),
                new ToolDefinition("knowledge", "vault", List.of(op("READ_WORKFLOW", "path"))),
                new ToolDefinition("browser", "browser", List.of(op("OPEN", "url")))));
        NativeToolLoopService service = new NativeToolLoopService(List.of(provider), manager, query -> ToolIntent.NO_TOOL,
                new ToolRuntimeProperties(true, 20, 20, 2, 30, "native"), bus, new ToolRuntimeDebugService(),
                new ObjectMapper(), new NativeToolSchemaMapper(registry), new StoreAuditDatasetService(bus));
        return service.execute(new ToolCallingRequest("spotify-play", "c", userMessage,
                delegatedGoal, "", Map.of(), "Base", new Brain(BrainType.FAST, "stub", "model", "stub", "", 0L, ReasoningLevel.LOW), KnowledgeMode.FAST, List.of(), history));
    }

    private ToolResult execute(ToolRequest request) {
        Map<String, Object> data;
        boolean success = true;
        String action = Objects.toString(request.arguments().get("action"), "");
        if (request.toolName().equals("knowledge")) {
            executed.add("knowledge/READ_WORKFLOW");
            success = !workflowMissing;
            data = Map.of("content", "Procedure: status, devices, search, play, verify", "parts", workflowPartial ? 2 : 1,
                    "part", 1, "version", "v1", "complete", !workflowPartial);
        } else if (request.operation().equals("OPEN")) {
            executed.add("open:" + request.arguments().get("target")); opened = true; data = Map.of("kind", "app");
        } else {
            executed.add(action);
            if (action.equals("devices")) deviceReads++;
            if (action.equals("current")) currentReads++;
            data = switch (action) {
                case "status" -> Map.of("connected", true);
                case "devices" -> Map.of("devices", opened && availableAfterOpen && deviceReads >= readinessReads ? List.of(Map.of("id", "local-id", "name", "Local desktop",
                        "type", "Computer", "local", true, "restricted", false)) : List.of());
                case "search" -> Map.of("tracks", Map.of("items", List.of(Map.of("uri", URI, "name", "Real search title"))));
                case "liked" -> Map.of("items", List.of(Map.of("uri", URI, "name", "Real liked title")));
                case "play" -> Map.of("accepted", true, "observedPlayback", playback(observed));
                case "current" -> Map.of("playback", playback(currentPlaying && currentReads >= currentReadyAfter));
                default -> throw new AssertionError("Unexpected execution: " + request);
            };
        }
        return new ToolResult(success, request.toolName(), request.operation(), "r", "c", false, List.of(), "result", data,
                success ? "" : "MISSING", "", false, "");
    }
    private Map<String, Object> playback(boolean playing) { return Map.of("isPlaying", playing, "deviceId", observedDevice, "item", Map.of("uri", observedUri, "name", actualTitle, "artists", List.of("Observed artist"))); }
    private static ToolOperationDefinition op(String name, String... args) {
        return new ToolOperationDefinition(name, name, Arrays.stream(args).map(a -> new ToolArgumentDefinition(a, "string", false, a)).toList(), true, ToolSafetyLevel.WRITE);
    }
    private ModelResponse workflow() { return call("knowledge__read_workflow", Map.of("path", "workflows/Spotify.md")); }
    private ModelResponse spotify(String action) { return call("pc__spotify", Map.of("action", action)); }
    private ModelResponse search() { return call("pc__spotify", Map.of("action", "search", "query", "something upbeat")); }
    private ModelResponse play(String uri) { return call("pc__spotify", Map.of("action", "play", "target", uri, "deviceId", "local-id")); }
    private ModelResponse open(String target) { return call("pc__open", Map.of("target", target)); }
    private static ModelResponse call(String name, Map<String, Object> args) { return new ModelResponse("", "", List.of(new ModelToolCall(UUID.randomUUID().toString(), name, args)), "tool_calls", new ModelUsage(0,0,0)); }
    private static ModelResponse answer(String text) { return new ModelResponse(text, "", List.of(), "stop", new ModelUsage(0,0,0)); }
}
