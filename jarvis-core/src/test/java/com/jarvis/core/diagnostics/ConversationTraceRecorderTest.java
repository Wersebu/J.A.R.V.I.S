package com.jarvis.core.diagnostics;

import com.jarvis.common.event.CognitiveEvent;
import com.jarvis.common.event.CognitiveEventType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationTraceRecorderTest {

    @TempDir
    Path directory;

    @Test
    void writesOneReadableBlockPerRequestWithTimingsToolsAndThinking() throws Exception {
        ConversationTraceRecorder recorder = new ConversationTraceRecorder(true, directory.toString(), 1500);
        List<CognitiveEvent> forwarded = new ArrayList<>();
        Consumer<CognitiveEvent> sink = recorder.begin("req-1", "conv/1", "wrzuć to na tv", forwarded::add);
        Instant t = Instant.now();

        sink.accept(event(t, CognitiveEventType.SOURCE_ADDED, "Knowledge source added to context", Map.of("relativePath", "workflows/Spotify.md")));
        sink.accept(event(t.plusMillis(100), CognitiveEventType.THINKING_TOKEN, "User wants ", Map.of()));
        sink.accept(event(t.plusMillis(4100), CognitiveEventType.THINKING_TOKEN, "the TV.", Map.of()));
        sink.accept(event(t.plusMillis(4200), CognitiveEventType.TOOL_EXECUTION_STARTED, "start",
                Map.of("tool", "pc", "operation", "SPOTIFY_PLAY", "arguments", Map.of("source", "current", "device", "telewizor"))));
        sink.accept(event(t.plusMillis(4300), CognitiveEventType.TOOL_EXECUTION_STARTED, "start",
                Map.of("tool", "pc", "operation", "SPOTIFY", "arguments", Map.of("action", "devices", "apiToken", "s3cret"))));
        sink.accept(event(t.plusMillis(4500), CognitiveEventType.TOOL_EXECUTION_FINISHED, "done",
                Map.of("tool", "pc", "operation", "SPOTIFY", "success", true, "message", "devices read", "data", "{\"devices\":2}")));
        sink.accept(event(t.plusMillis(6200), CognitiveEventType.TOOL_EXECUTION_FINISHED, "done",
                Map.of("tool", "pc", "operation", "SPOTIFY_PLAY", "success", true, "message", "Moved to FrytaCraft.pl")));
        sink.accept(event(t.plusMillis(6300), CognitiveEventType.ANSWER_TOKEN, "Gra na TV.", Map.of()));
        recorder.finish();

        assertThat(forwarded).hasSize(8);
        String trace = Files.readString(directory.resolve("conv_1.md"));
        assertThat(trace).startsWith("# Ślad rozmowy conv/1")
                .contains("wrzuć to na tv", "workflows/Spotify.md", "MYŚLENIE (4.0s)", "User wants the TV.",
                        "NARZĘDZIE (2.0s)", "pc.SPOTIFY_PLAY", "\"device\":\"telewizor\"", "Moved to FrytaCraft.pl",
                        "↳ krok (0.2s)", "devices read", "[redacted]", "ODPOWIEDŹ", "Gra na TV.",
                        "narzędzia 2.0s (1 wywołań)")
                .doesNotContain("s3cret");
    }

    @Test
    void mailContentNeverReachesTheTraceFile() throws Exception {
        ConversationTraceRecorder recorder = new ConversationTraceRecorder(true, directory.toString(), 1500);
        Consumer<CognitiveEvent> sink = recorder.begin("req-1", "conv/2", "co nowego na poczcie?", event -> { });
        Instant t = Instant.now();
        sink.accept(event(t, CognitiveEventType.TOOL_EXECUTION_STARTED, "start",
                Map.of("tool", "gmail", "operation", "UNREAD", "arguments", Map.of())));
        sink.accept(event(t.plusMillis(300), CognitiveEventType.TOOL_EXECUTION_FINISHED, "done",
                Map.of("tool", "gmail", "operation", "UNREAD", "success", true, "message", "Gmail unread read: 1 message(s).",
                        "data", "{\"messages\":[{\"subject\":\"Wyniki badan\",\"body\":\"Prywatna tresc\"}]}")));
        recorder.finish();

        String trace = Files.readString(directory.resolve("conv_2.md"));
        assertThat(trace).contains("gmail.UNREAD", "1 message(s)", "[treść poczty pominięta]")
                .doesNotContain("Wyniki badan").doesNotContain("Prywatna tresc");
    }

    @Test
    void disabledRecorderPassesTheSinkThroughAndWritesNothing() throws Exception {
        ConversationTraceRecorder recorder = new ConversationTraceRecorder(false, directory.toString(), 1500);
        Consumer<CognitiveEvent> sink = event -> { };
        assertThat(recorder.begin("req", "conv", "hej", sink)).isSameAs(sink);
        recorder.finish();
        try (var files = Files.list(directory)) {
            assertThat(files).isEmpty();
        }
    }

    private static CognitiveEvent event(Instant at, CognitiveEventType type, String message, Map<String, Object> metadata) {
        return new CognitiveEvent("req-1", "conv/1", at, type, "S", message, null, "", null, metadata);
    }
}
