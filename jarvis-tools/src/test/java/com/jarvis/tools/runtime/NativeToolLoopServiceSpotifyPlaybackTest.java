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
    private boolean missingRoutedPath;
    private boolean noCandidates;
    private String candidatePath = "procedures/Spotify playback.md";
    private boolean workflowPartial;
    private String userMessage = "odpalisz mi coś fajnego na spotify?";
    private String delegatedGoal = "Play music";
    private String history = "";
    private int readinessReads;
    private int deviceReads;
    private int currentReads;
    private int currentReadyAfter;
    private String actualTitle = "";
    private boolean firstLikedUnplayable;
    private Map<String, Object> playArguments = Map.of();
    private boolean tvVisible;
    private boolean followPlayDevice;
    private boolean transferStartsAnotherTrack;
    private Map<String, Object> requestContext = Map.of();
    private final List<String> offeredTools = new ArrayList<>();
    private AIProvider provider;


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

    @Test void discoveryReadsExactCandidateAndPlaysLikedSongsDespiteWrongDelegation() {
        userMessage = "odpal mi coś z polubionych na spotify";
        delegatedGoal = "Play a track from user's favorite playlists on Spotify";
        opened = true;
        ToolCallingResult result = run(findWorkflow(), readWorkflow(candidatePath), spotify("status"), spotify("liked"),
                spotify("devices"), play(URI), answer("Done"));
        assertThat(executed).containsExactly("knowledge/FIND_WORKFLOW", "knowledge/READ_WORKFLOW", "status", "liked", "devices", "play");
        assertThat(result.terminationInfo().completed()).isTrue();
        assertThat(result.finalAnswer()).contains("Real liked title", "Local desktop");
    }

    @Test void missingDocumentRequiresDiscoveryInsteadOfMoreGuessedPaths() {
        missingRoutedPath = true;
        opened = true;
        ToolCallingResult result = run(workflow(), readWorkflow("spotify/workflows/GraphicDesignWorkflow.md"),
                findWorkflow(), readWorkflow(candidatePath), spotify("status"), spotify("devices"), search(), play(URI), answer("Done"));
        assertThat(executed).containsExactly("knowledge/READ_WORKFLOW", "knowledge/FIND_WORKFLOW", "knowledge/READ_WORKFLOW",
                "status", "devices", "search", "play");
        assertThat(result.steps()).anyMatch(s -> s.action().equals("SPOTIFY_POLICY_BLOCKED")
                && s.arguments().containsValue("spotify/workflows/GraphicDesignWorkflow.md"));
        assertThat(result.terminationInfo().completed()).isTrue();
    }

    @Test void candidatePathCannotBePrefixedAndPartialDiscoveryReadCannotAuthorizePlayback() {
        workflowPartial = true;
        ToolCallingResult result = run(findWorkflow(), readWorkflow("spotify/" + candidatePath), readWorkflow(candidatePath),
                spotify("status"), answer("Gotowe"), answer("Gotowe"));
        assertThat(executed).containsExactly("knowledge/FIND_WORKFLOW", "knowledge/READ_WORKFLOW");
        assertThat(result.terminationInfo().completed()).isFalse();
    }

    @Test void announcingSearchWithoutToolCallDoesNotExecuteDiscoveryOrProveWorkflowRead() {
        ToolCallingResult result = run(readWorkflow("spotify/Spotify.md"),
                answer("Let's search for Spotify using knowledge__find_workflow."),
                answer("Let's search for Spotify using knowledge__find_workflow."));
        assertThat(executed).isEmpty();
        assertThat(result.terminationInfo().completed()).isFalse();
        assertThat(result.finalAnswer()).contains("Nie odczytano kompletnej procedury");
    }

    @Test void noCandidatesCannotBeReplacedWithTheOldRoutingHint() {
        noCandidates = true;
        ToolCallingResult result = run(findWorkflow(), workflow(), spotify("status"), answer("Gotowe"), answer("Gotowe"));
        assertThat(executed).containsExactly("knowledge/FIND_WORKFLOW");
        assertThat(result.terminationInfo().completed()).isFalse();
        assertThat(result.finalAnswer()).contains("Nie odczytano kompletnej procedury");
    }

    @Test void everyPartOfDiscoveredProcedureMustBeReadBeforePlayback() {
        workflowPartial = true;
        opened = true;
        ToolCallingResult result = run(findWorkflow(), readWorkflow(candidatePath),
                call("knowledge__read_workflow", Map.of("path", candidatePath, "part", "2")),
                spotify("status"), spotify("devices"), search(), play(URI), answer("Done"));
        assertThat(result.terminationInfo().completed()).isTrue();
        assertThat(Collections.frequency(executed, "knowledge/READ_WORKFLOW")).isEqualTo(2);
    }

    @Test void genericRequestStoppingAfterDevicesIsSteeredToLikedAndPlaysOnLocalPc() {
        userMessage = "siemanko odpal mi coś na spotify";
        opened = true;
        ToolCallingResult result = run(findWorkflow(), readWorkflow(candidatePath), spotify("status"), spotify("devices"),
                answer("Nie wiem, co lubisz. Co mam włączyć?"), spotify("liked"),
                call("pc__spotify", Map.of("action", "play", "target", URI, "deviceName", "Local desktop")), answer("Done"));
        assertThat(executed).containsExactly("knowledge/FIND_WORKFLOW", "knowledge/READ_WORKFLOW", "status", "devices", "liked", "play");
        assertThat(playArguments).containsEntry("context", "liked").containsEntry("deviceId", "local-id");
        assertThat(result.terminationInfo().completed()).isTrue();
        assertThat(result.finalAnswer()).contains("Potwierdzono odtwarzanie", "Real liked title", "Local desktop");
    }

    @Test void searchBeforeDevicesAndMissingOrWrongDeviceIdAreHandledByCore() {
        opened = true;
        ToolCallingResult result = run(workflow(), spotify("status"), search(), spotify("devices"),
                call("pc__spotify", Map.of("action", "play", "target", URI, "deviceId", "hallucinated-id")), answer("Done"));
        assertThat(executed).containsExactly("knowledge/READ_WORKFLOW", "status", "search", "devices", "play");
        assertThat(result.steps()).noneMatch(s -> s.action().equals("SPOTIFY_POLICY_BLOCKED"));
        assertThat(result.terminationInfo().completed()).isTrue();
    }

    @Test void returnedLinkOrResultNameMapsToItsUriButUnknownTargetStaysBlocked() {
        for (String target : List.of("https://open.spotify.com/intl-pl/track/" + "A".repeat(22) + "?si=x", "real search TITLE")) {
            executed.clear(); opened = true;
            ToolCallingResult result = run(workflow(), spotify("status"), spotify("devices"), search(),
                    call("pc__spotify", Map.of("action", "play", "target", target)), answer("Done"));
            assertThat(executed).endsWith("search", "play");
            assertThat(result.terminationInfo().completed()).isTrue();
        }
        executed.clear(); opened = true;
        ToolCallingResult result = run(workflow(), spotify("status"), spotify("devices"), search(),
                call("pc__spotify", Map.of("action", "play", "target", "https://open.spotify.com/track/" + "B".repeat(22))),
                answer("Gra"), answer("Gra"));
        assertThat(executed).doesNotContain("play");
        assertThat(result.terminationInfo().completed()).isFalse();
    }

    @Test void miscopiedUriAndTruncatedDeviceIdFromSmallModelResolveToReturnedResults() {
        userMessage = "odpal mi coś na spotify";
        opened = true;
        String typo = "spotify:track:" + "A".repeat(10) + "a" + "A".repeat(10); // one case slip + one dropped char
        ToolCallingResult result = run(workflow(), spotify("status"), spotify("devices"), spotify("liked"),
                call("pc__spotify", Map.of("action", "play", "target", typo, "deviceId", "local-i")), answer("Done"));
        assertThat(executed).endsWith("liked", "play");
        assertThat(result.terminationInfo().completed()).isTrue();
        assertThat(result.finalAnswer()).contains("Real liked title");
    }

    @Test void preferencesReadAfterProcedureIsAllowedAndUnplayableLikedTrackIsSkipped() {
        userMessage = "odpal mi coś na spotify";
        opened = true; firstLikedUnplayable = true;
        ToolCallingResult result = run(findWorkflow(), readWorkflow(candidatePath),
                call("knowledge__read_document", Map.of("path", "preferences/Music.md")),
                call("pc__read", Map.of("path", "preferences/Music.md")),
                spotify("status"), spotify("devices"), spotify("liked"), play(INVENTED), play(URI), answer("Done"));
        assertThat(executed).containsExactly("knowledge/FIND_WORKFLOW", "knowledge/READ_WORKFLOW", "knowledge/READ_DOCUMENT",
                "status", "devices", "liked", "play");
        assertThat(result.terminationInfo().completed()).isTrue();
        assertThat(result.finalAnswer()).contains("Real liked title");
    }

    @Test void modelDecidesOnceThenSpotifyPlayRunsProcedureAndModelAnswersInItsOwnWords() {
        userMessage = "siemka odpal mi coś na spotify";
        ToolCallingResult result = run(call("pc__spotify_play", Map.of("source", "liked")),
                answer("Leci Real liked title na Twoim komputerze."));
        assertThat(executed).containsExactly("knowledge/FIND_WORKFLOW", "knowledge/READ_WORKFLOW", "status", "devices",
                "open:spotify", "devices", "liked", "play");
        assertThat(playArguments).containsEntry("target", URI).containsEntry("deviceId", "local-id").containsEntry("context", "liked");
        assertThat(result.terminationInfo().completed()).isTrue();
        assertThat(result.finalAnswer()).isEqualTo("Leci Real liked title na Twoim komputerze.");
        verify(provider, times(2)).toolChat(any(), anyList(), anyList(), any());
    }

    @Test void modelReplyNotNamingTheObservedTrackIsReplacedByEvidence() {
        userMessage = "odpal coś na spotify";
        opened = true;
        ToolCallingResult result = run(call("pc__spotify_play", Map.of()), answer("Gotowe, gra muzyka z https://open.spotify.com/x"));
        assertThat(result.finalAnswer()).contains("Potwierdzono odtwarzanie", "Real liked title", "Local desktop");
    }

    @Test void spotifyPlayVerifiesWithCurrentAndNeverClaimsUnobservedPlayback() {
        userMessage = "puść coś z polubionych na spotify";
        opened = true; observed = false; currentPlaying = false;
        ToolCallingResult result = run(call("pc__spotify_play", Map.of("source", "liked")),
                call("pc__spotify_play", Map.of("source", "liked")), answer("Gra Real liked title!"), answer("Gra Real liked title!"));
        assertThat(executed).endsWith("liked", "play", "current", "current");
        assertThat(Collections.frequency(executed, "play")).isEqualTo(1);
        assertThat(result.terminationInfo().completed()).isFalse();
        assertThat(result.finalAnswer()).contains("Zadanie nieukończone");
    }

    @Test void spotifyPlaySearchPlaysBestMatch() {
        userMessage = "puść Uptown Funk na spotify";
        opened = true;
        ToolCallingResult result = run(call("pc__spotify_play", Map.of("source", "search", "query", "Uptown Funk")), answer("Done"));
        assertThat(executed).endsWith("devices", "search", "play");
        assertThat(playArguments).containsEntry("target", URI).doesNotContainKey("context");
        assertThat(result.terminationInfo().completed()).isTrue();
        assertThat(result.finalAnswer()).contains("Real search title");
    }

    @Test void honestExplanationOfAMissingTvReachesTheUserButAPlaybackClaimNeverDoes() {
        userMessage = "a dasz radę odpalić to na telewizorze na spotify?";
        opened = true;
        String honest = "Nie widzę telewizora w urządzeniach Spotify — dostępny jest tylko Local desktop.";
        ToolCallingResult result = run(call("pc__spotify_play", Map.of("source", "liked", "device", "telewizor")),
                answer(honest), answer(honest), answer(honest));
        assertThat(executed).doesNotContain("play");
        assertThat(result.steps()).anyMatch(s -> s.operation().equals("SPOTIFY_PLAY")
                && s.result().errorCode().equals("NO_DEVICE") && s.result().data().get("visibleDevices").toString().contains("Local desktop"));
        assertThat(result.finalAnswer()).isEqualTo(honest);

        executed.clear();
        result = run(call("pc__spotify_play", Map.of("source", "liked", "device", "telewizor")),
                answer("Nie widzę TV, więc włączyłem na komputerze — już gra."), answer("Nie widzę TV, więc włączyłem na komputerze — już gra."));
        assertThat(result.finalAnswer()).contains("Zadanie nieukończone");
    }

    @Test void nextTrackOnTheTvIsOneCallWithoutProcedureOrDeviceDiscovery() {
        userMessage = "puść następną piosenkę na spotify";
        observedDevice = "tv-id";
        ToolCallingResult result = run(call("pc__spotify_control", Map.of("action", "next")), answer("Leci teraz Next title."));
        assertThat(executed).containsExactly("current", "next");
        assertThat(result.terminationInfo().completed()).isTrue();
        assertThat(result.finalAnswer()).isEqualTo("Leci teraz Next title.");
        assertThat(result.steps()).anyMatch(s -> s.operation().equals("SPOTIFY_CONTROL") && s.result().success()
                && "Next title".equals(s.result().data().get("title")));
    }

    @Test void controlWithNothingPlayingIsReportedHonestly() {
        userMessage = "puść następną na spotify";
        observedDevice = "";
        String honest = "Nie mogę przełączyć, bo na Spotify nic teraz nie gra.";
        ToolCallingResult result = run(call("pc__spotify_control", Map.of("action", "next")), answer(honest));
        assertThat(executed).containsExactly("current");
        assertThat(result.finalAnswer()).isEqualTo(honest);
    }

    @Test void deviceTheUserNamedWinsWhenTheModelLeavesItOutAndToMeansWhatIsPlaying() {
        userMessage = "A odpal to na telewizorze.";
        history = "User: odpal coś na spotify";
        tvVisible = true; opened = true; followPlayDevice = true; actualTitle = "Actual observed title";
        ToolCallingResult result = run(call("pc__spotify_play", Map.of("source", "liked")), answer("Actual observed title gra na telewizorze."));
        assertThat(executed).endsWith("devices", "current", "play").doesNotContain("liked");
        assertThat(playArguments).containsEntry("deviceId", "tv-id").containsEntry("transfer", "true");
        assertThat(result.terminationInfo().completed()).isTrue();
    }

    @Test void replyClaimingTheTvWhileThePcPlaysIsReplacedByEvidence() {
        userMessage = "odpal coś na spotify";
        tvVisible = true; opened = true;
        ToolCallingResult result = run(call("pc__spotify_play", Map.of("source", "liked")),
                answer("Real liked title gra teraz na Twoim telewizorze FrytaCraft.pl."));
        assertThat(playArguments).containsEntry("deviceId", "local-id");
        assertThat(result.finalAnswer()).contains("Potwierdzono odtwarzanie", "Urządzenie lokalnego PC: Local desktop")
                .doesNotContain("telewizor");
    }

    @Test void shortFollowUpControlIsGroundedEvenWithoutNamingSpotify() {
        userMessage = "next";
        history = "User: odpal coś na spotify";
        ToolCallingResult result = run(call("pc__spotify_control", Map.of("action", "next")), answer("Leci dalej BROTA NA BASE."));
        assertThat(executed).containsExactly("current", "next");
        assertThat(result.finalAnswer()).contains("Next title").doesNotContain("BROTA");
    }

    @Test void whatIsPlayingIsOneReadAndTheReplyMustNameTheTrack() {
        userMessage = "co to za piosenka?";
        history = "User: odpal coś na spotify";
        actualTitle = "Actual observed title";
        ToolCallingResult result = run(call("pc__spotify_control", Map.of("action", "now")), answer("To Inny tytuł."));
        assertThat(executed).containsExactly("current");
        assertThat(result.finalAnswer()).contains("Actual observed title", "Observed artist").doesNotContain("Inny tytuł");
    }

    @Test void ownPlaylistIsFoundByNameInTheLibrary() {
        userMessage = "puść moją playlistę siłownia na spotify";
        opened = true;
        run(call("pc__spotify_play", Map.of("source", "library", "query", "siłownia")), answer("Done"), answer("Done"));
        assertThat(executed).contains("library", "play");
        assertThat(playArguments).containsEntry("target", "spotify:playlist:" + "Q".repeat(22));
    }

    @Test void queueModeAddsAfterTheCurrentTrackWithoutInterruptingIt() {
        userMessage = "dodaj Uptown Funk do kolejki na spotify";
        opened = true;
        ToolCallingResult result = run(call("pc__spotify_play", Map.of("source", "search", "query", "Uptown Funk", "mode", "queue")),
                answer("Dodane."));
        assertThat(executed).endsWith("search", "current", "enqueue").doesNotContain("play");
        assertThat(result.finalAnswer()).contains("Added to the queue: Real search title");
    }

    @Test void rawTransferWithTheTvIdBecomesOneVerifiedMoveOfTheCurrentTrack() {
        userMessage = "oki teraz wrzuć to na telewizor";
        history = "User: odpal mi coś na spotify";
        tvVisible = true; opened = true; followPlayDevice = true; actualTitle = "Actual observed title";
        ToolCallingResult result = run(call("pc__spotify", Map.of("action", "transfer", "deviceId", "tv-id")),
                answer("Actual observed title gra teraz na telewizorze."));
        assertThat(executed).endsWith("devices", "current", "play");
        assertThat(playArguments).containsEntry("deviceId", "tv-id").containsEntry("transfer", "true");
        assertThat(result.terminationInfo().completed()).isTrue();
        assertThat(result.finalAnswer()).isEqualTo("Actual observed title gra teraz na telewizorze.");
    }

    @Test void movingToTheTvIsConfirmedWhenItPlaysThereEvenIfSmartShuffleStartsAnotherTrack() {
        userMessage = "wrzuć to na tv";
        history = "User: odpal coś na spotify";
        tvVisible = true; opened = true; followPlayDevice = true; transferStartsAnotherTrack = true; actualTitle = "Nagano 98";
        ToolCallingResult result = run(call("pc__spotify_play", Map.of("source", "current", "device", "telewizor")),
                answer("Nagano 98 gra teraz na telewizorze."));
        assertThat(result.terminationInfo().completed()).isTrue();
        assertThat(result.finalAnswer()).contains("Przeniesiono odtwarzanie Spotify na FrytaCraft.pl", "Alien").doesNotContain("Nagano");
    }

    @Test void preloadedWorkflowLimitsTheOfferedToolsToTheOnesItNames() {
        userMessage = "odpal coś na spotify";
        requestContext = Map.of("workflowTools", List.of("pc__spotify_play", "pc__spotify_control", "pc__spotify"));
        run(call("pc__spotify_play", Map.of("source", "liked")), answer("Done"));
        assertThat(offeredTools).contains("pc__spotify_play", "pc__spotify", "knowledge__find_workflow", "knowledge__read_workflow")
                .doesNotContain("pc__open", "pc__shell", "browser__open");
    }

    @Test void rawNextOrPauseIsTheOneCallControl() {
        userMessage = "następna";
        history = "User: odpal coś na spotify";
        ToolCallingResult result = run(call("pc__spotify", Map.of("action", "next")), answer("Leci Next title."));
        assertThat(executed).containsExactly("current", "next");
        assertThat(result.steps()).anyMatch(s -> s.operation().equals("SPOTIFY_CONTROL"));
        assertThat(result.finalAnswer()).isEqualTo("Leci Next title.");
    }

    @Test void fromTheTvBackToThePcKeepsThePc() {
        userMessage = "przenieś muzykę z telewizora na komputer";
        history = "User: odpal coś na spotify";
        tvVisible = true; opened = true; followPlayDevice = true; observedDevice = "tv-id"; actualTitle = "Actual observed title";
        run(call("pc__spotify_play", Map.of("source", "current", "device", "komputer")), answer("Done"), answer("Done"));
        assertThat(playArguments).containsEntry("deviceId", "local-id").containsEntry("transfer", "true");
    }

    @Test void currentPlaybackMovesToTheTvWithItsQueueAndPosition() {
        userMessage = "a dasz radę odpalić to na TV na spotify?";
        tvVisible = true; opened = true; followPlayDevice = true; actualTitle = "Actual observed title";
        ToolCallingResult result = run(call("pc__spotify_play", Map.of("source", "current", "device", "TV")),
                answer("Przeniosłem Actual observed title na telewizor."));
        assertThat(executed).endsWith("devices", "current", "play");
        assertThat(playArguments).containsEntry("target", URI).containsEntry("deviceId", "tv-id").containsEntry("transfer", "true");
        assertThat(result.terminationInfo().completed()).isTrue();
    }

    @Test void anotherDevicePlaysOnlyWhenTheUserAskedForIt() {
        userMessage = "odpal coś ze spotify na telewizorze";
        tvVisible = true; observedDevice = "tv-id";
        ToolCallingResult result = run(call("pc__spotify_play", Map.of("source", "liked", "device", "telewizor")), answer("Done"));
        assertThat(playArguments).containsEntry("deviceId", "tv-id");
        assertThat(executed).doesNotContain("open:spotify");
        assertThat(result.terminationInfo().completed()).isTrue();
        assertThat(result.finalAnswer()).contains("Urządzenie: FrytaCraft.pl");

        executed.clear(); playArguments = Map.of();
        userMessage = "odpal coś na spotify";
        result = run(call("pc__spotify_play", Map.of("source", "liked", "device", "FrytaCraft.pl")), answer("Gra na TV"), answer("Gra na TV"));
        assertThat(executed).doesNotContain("play");
        assertThat(result.terminationInfo().completed()).isFalse();
    }

    private ModelResponse findWorkflow() { return call("knowledge__find_workflow", Map.of("query", "Spotify")); }
    private ModelResponse readWorkflow(String path) { return call("knowledge__read_workflow", Map.of("path", path)); }

    private ToolCallingResult run(ModelResponse... turns) {
        Deque<ModelResponse> script = new ArrayDeque<>(List.of(turns));
        provider = mock(AIProvider.class);
        when(provider.provider()).thenReturn("stub");
        when(provider.toolChat(any(), anyList(), anyList(), any())).thenAnswer(inv -> {
            List<NativeToolDefinition> definitions = inv.getArgument(2);
            offeredTools.clear();
            definitions.forEach(definition -> offeredTools.add(definition.name()));
            assertThat(definitions).extracting(NativeToolDefinition::name).contains("knowledge__find_workflow", "knowledge__read_workflow");
            return script.isEmpty() ? answer("") : script.removeFirst();
        });
        ToolManager manager = mock(ToolManager.class);
        when(manager.findTool(anyString())).thenReturn(Optional.of(mock(JarvisTool.class)));
        when(manager.execute(any())).thenAnswer(inv -> execute(inv.getArgument(0)));
        CognitiveEventBus bus = mock(CognitiveEventBus.class);
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.definitions()).thenReturn(List.of(
                new ToolDefinition("pc", "desktop", List.of(op("SPOTIFY", "action", "target", "query", "deviceId", "deviceName"), op("SPOTIFY_PLAY", "source", "query", "type", "owner", "pick", "mode", "device"), op("SPOTIFY_CONTROL", "action", "value", "state"), op("OPEN", "target"), op("SHELL", "command"))),
                new ToolDefinition("knowledge", "vault", List.of(op("READ_WORKFLOW", "path", "part"), op("FIND_WORKFLOW", "query"), op("READ_DOCUMENT", "path"))),
                new ToolDefinition("browser", "browser", List.of(op("OPEN", "url")))));
        NativeToolLoopService service = new NativeToolLoopService(List.of(provider), manager, query -> ToolIntent.NO_TOOL,
                new ToolRuntimeProperties(true, 20, 20, 2, 30, "native"), bus, new ToolRuntimeDebugService(),
                new ObjectMapper(), new NativeToolSchemaMapper(registry), new StoreAuditDatasetService(bus));
        service.spotifyPollMillis(0);
        return service.execute(new ToolCallingRequest("spotify-play", "c", userMessage,
                delegatedGoal, "", requestContext, "Base", new Brain(BrainType.FAST, "stub", "model", "stub", "", 0L, ReasoningLevel.LOW), KnowledgeMode.FAST, List.of(), history));
    }

    private ToolResult execute(ToolRequest request) {
        Map<String, Object> data;
        boolean success = true;
        String action = Objects.toString(request.arguments().get("action"), "");
        if (request.toolName().equals("knowledge")) {
            executed.add("knowledge/" + request.operation());
            if (request.operation().equals("FIND_WORKFLOW")) {
                return new ToolResult(true, "knowledge", "FIND_WORKFLOW", "r", "c", false, List.of(), "Candidates found",
                        Map.of("candidates", noCandidates ? List.of() : List.of(new com.jarvis.knowledge.vault.read.WorkflowCandidate("doc-id", candidatePath,
                                "Spotify playback", "active", "v1", "", 100, 1, 1.0, List.of()))), "", "", false, "");
            }
            success = !workflowMissing && !(missingRoutedPath && request.arguments().get("path").equals("workflows/Spotify.md"));
            data = Map.of("content", "Procedure: status, devices, search, play, verify", "parts", workflowPartial ? 2 : 1,
                    "part", Integer.parseInt(Objects.toString(request.arguments().getOrDefault("part", "1"))), "version", "v1", "complete", !workflowPartial);
        } else if (request.operation().equals("OPEN")) {
            executed.add("open:" + request.arguments().get("target")); opened = true; data = Map.of("kind", "app");
        } else {
            executed.add(action);
            if (action.equals("devices")) deviceReads++;
            if (action.equals("current")) currentReads++;
            data = switch (action) {
                case "status" -> Map.of("connected", true);
                case "devices" -> {
                    List<Map<String, Object>> devices = new ArrayList<>();
                    if (opened && availableAfterOpen && deviceReads >= readinessReads) devices.add(Map.of("id", "local-id", "name", "Local desktop",
                            "type", "Computer", "local", true, "restricted", false));
                    if (tvVisible) devices.add(Map.of("id", "tv-id", "name", "FrytaCraft.pl", "type", "TV", "local", false, "restricted", false));
                    yield Map.of("devices", devices);
                }
                case "search" -> Map.of("tracks", Map.of("items", List.of(Map.of("uri", URI, "name", "Real search title"))));
                case "liked" -> Map.of("items", firstLikedUnplayable
                        ? List.of(Map.of("uri", INVENTED, "name", "Region locked", "is_playable", false), Map.of("uri", URI, "name", "Real liked title"))
                        : List.of(Map.of("uri", URI, "name", "Real liked title")));
                case "play" -> { playArguments = request.arguments();
                    if (followPlayDevice) observedDevice = String.valueOf(request.arguments().get("deviceId"));
                    if (transferStartsAnotherTrack) { observedUri = "spotify:track:" + "D".repeat(22); actualTitle = "Alien"; } yield Map.of("accepted", true, "observedPlayback", playback(observed)); }
                case "current" -> Map.of("playback", playback(currentPlaying && currentReads >= currentReadyAfter));
                case "enqueue", "save" -> Map.of("accepted", true);
                case "library" -> Map.of("items", List.of(Map.of("uri", "spotify:playlist:" + "P".repeat(22), "name", "Chill"),
                        Map.of("uri", "spotify:playlist:" + "Q".repeat(22), "name", "Siłownia 2024")));
                case "next" -> {
                    observedUri = "spotify:track:" + "C".repeat(22); actualTitle = "Next title";
                    yield Map.of("accepted", true, "observedPlayback", playback(true));
                }
                default -> throw new AssertionError("Unexpected execution: " + request);
            };
        }
        return new ToolResult(success, request.toolName(), request.operation(), "r", "c", false, List.of(), "result", data,
                success ? "" : "MISSING", "", false, "");
    }
    private Map<String, Object> playback(boolean playing) { return Map.of("isPlaying", playing, "deviceId", observedDevice, "progressMs", 42000, "item", Map.of("uri", observedUri, "name", actualTitle, "artists", List.of("Observed artist"))); }
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
