package com.jarvis.core.diagnostics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.common.event.CognitiveEvent;
import com.jarvis.common.event.CognitiveEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Writes one readable Markdown trace per conversation ({@code data/traces/<conversationId>.md}): for every
 * request the user's message, the knowledge handed to the model, each thinking block with its duration, the
 * decisions, every tool call with arguments, result and duration, the answer, and a summary of where the time
 * went. Built from the request's existing cognitive event stream, so it records exactly what the UI saw.
 * Meant for diagnosis: the file can contain tool arguments and results, so keep it on the server.
 */
@Service
public class ConversationTraceRecorder {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConversationTraceRecorder.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final Pattern SECRET_KEY = Pattern.compile("(?i)authoriz|api[-_]?key|password|passwd|cookie|secret|token");
    /** Per-token and per-stage noise: tokens are merged into blocks, the rest adds nothing a reader needs. */
    private static final Set<CognitiveEventType> SKIPPED = Set.of(CognitiveEventType.TOKEN, CognitiveEventType.STAGE_STARTED,
            CognitiveEventType.WAITING_FIRST_TOKEN, CognitiveEventType.THINKING_STARTED, CognitiveEventType.THINKING_FINISHED,
            CognitiveEventType.ANSWER_STARTED, CognitiveEventType.ANSWER_FINISHED, CognitiveEventType.STREAMING_FINISHED);

    /** Tools whose results are the user's private content (mail), kept out of trace files. */
    private static final Set<String> PRIVATE_TOOLS = Set.of("gmail");

    private final boolean enabled;
    private final Path directory;
    private final int maxFieldChars;
    private final ThreadLocal<Trace> current = new ThreadLocal<>();

    public ConversationTraceRecorder(
            @Value("${jarvis.diagnostics.conversation-trace.enabled:true}") boolean enabled,
            @Value("${jarvis.diagnostics.conversation-trace.directory:data/traces}") String directory,
            @Value("${jarvis.diagnostics.conversation-trace.max-field-chars:1500}") int maxFieldChars
    ) {
        this.enabled = enabled;
        this.directory = Path.of(directory);
        this.maxFieldChars = Math.max(200, maxFieldChars);
    }

    /**
     * Starts recording a request on this thread and returns the sink to use in place of {@code sink}.
     *
     * @param requestId request id
     * @param conversationId conversation id
     * @param message the user's message
     * @param sink the request's original event sink
     * @return a sink that forwards to {@code sink} and records
     */
    public Consumer<CognitiveEvent> begin(String requestId, String conversationId, String message, Consumer<CognitiveEvent> sink) {
        if (!enabled) return sink;
        Trace trace = new Trace(requestId, conversationId, message == null ? "" : message, Instant.now());
        current.set(trace);
        return event -> {
            sink.accept(event);
            try {
                trace.record(event);
            } catch (RuntimeException exception) {
                LOGGER.debug("[CONVERSATION_TRACE] event skipped: {}", exception.getMessage());
            }
        };
    }

    /** Writes the request started on this thread to its conversation file. */
    public void finish() {
        Trace trace = current.get();
        current.remove();
        if (trace == null) return;
        try {
            Files.createDirectories(directory);
            Path file = directory.resolve(trace.conversationId.replaceAll("[^A-Za-z0-9._-]", "_") + ".md");
            String header = Files.exists(file) ? "" : "# Ślad rozmowy " + trace.conversationId + "\n\n";
            Files.writeString(file, header + render(trace), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            LOGGER.info("[CONVERSATION_TRACE] requestId={} file={}", trace.requestId, file.toAbsolutePath());
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("[CONVERSATION_TRACE] write failed requestId={} error={}", trace.requestId, exception.getMessage());
        }
    }

    String render(Trace trace) {
        trace.close();
        Instant end = Instant.now();
        StringBuilder out = new StringBuilder();
        out.append("## ").append(CLOCK.format(trace.started)).append(" — ").append(oneLine(trace.message, 200)).append("\n\n");
        out.append("- requestId: `").append(trace.requestId).append("`\n");
        out.append("- wiadomość: ").append(quote(trace.message)).append("\n\n");
        out.append("| czas | co | szczegóły |\n|---|---|---|\n");
        long thinkingMs = 0, toolMs = 0, answerMs = 0;
        int thinkingBlocks = 0, toolCalls = 0;
        for (Entry entry : trace.entries) {
            long durationMs = entry.end == null ? 0 : Duration.between(entry.start, entry.end).toMillis();
            String at = "+" + seconds(Duration.between(trace.started, entry.start).toMillis());
            String what = entry.kind + (durationMs > 0 ? " (" + seconds(durationMs) + ")" : "");
            out.append("| ").append(at).append(" | ").append(what).append(" | ").append(cell(entry.text)).append(" |\n");
            switch (entry.kind) {
                case "MYŚLENIE" -> { thinkingMs += durationMs; thinkingBlocks++; }
                case "NARZĘDZIE" -> { toolMs += durationMs; toolCalls++; }
                case "ODPOWIEDŹ" -> answerMs += durationMs;
                default -> { }
            }
        }
        long totalMs = Duration.between(trace.started, end).toMillis();
        out.append("\n**Podsumowanie:** całość ").append(seconds(totalMs))
                .append(" · myślenie modelu ").append(seconds(thinkingMs)).append(" w ").append(thinkingBlocks).append(" blokach")
                .append(" · narzędzia ").append(seconds(toolMs)).append(" (").append(toolCalls).append(" wywołań)")
                .append(" · strumień odpowiedzi ").append(seconds(answerMs))
                .append(" · reszta ").append(seconds(Math.max(0, totalMs - thinkingMs - toolMs - answerMs))).append("\n\n---\n\n");
        return out.toString();
    }

    private String seconds(long millis) {
        return String.format(java.util.Locale.ROOT, "%.1fs", millis / 1000.0);
    }

    private String cell(String text) {
        return text.replace("|", "\\|").replace("\r", "").replace("\n", "<br>");
    }

    private String quote(String text) {
        return "„" + oneLine(text, maxFieldChars) + "”";
    }

    private String oneLine(String text, int limit) {
        String flat = String.valueOf(text).replaceAll("\\s+", " ").strip();
        return flat.length() <= limit ? flat : flat.substring(0, limit) + "…";
    }

    private String compact(Object value) {
        try {
            String json = JSON.writeValueAsString(redact(value));
            return json.length() <= maxFieldChars ? json : json.substring(0, maxFieldChars) + "…";
        } catch (Exception exception) {
            return oneLine(String.valueOf(value), maxFieldChars);
        }
    }

    private Object redact(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, child) -> copy.put(String.valueOf(key),
                    SECRET_KEY.matcher(String.valueOf(key)).find() ? "[redacted]" : redact(child)));
            return copy;
        }
        if (value instanceof List<?> list) return list.stream().map(this::redact).toList();
        return value;
    }

    /** One timeline row; token streams and tool executions become a single row with a duration. */
    private static final class Entry {
        final String kind;
        final Instant start;
        Instant end;
        String text;

        Entry(String kind, Instant start, String text) {
            this.kind = kind;
            this.start = start;
            this.text = text;
        }
    }

    final class Trace {
        final String requestId;
        final String conversationId;
        final String message;
        final Instant started;
        final List<Entry> entries = new ArrayList<>();
        private final Deque<Entry> runningTools = new ArrayDeque<>();
        private Entry stream;
        private StringBuilder streamText;

        Trace(String requestId, String conversationId, String message, Instant started) {
            this.requestId = requestId;
            this.conversationId = conversationId;
            this.message = message;
            this.started = started;
        }

        synchronized void record(CognitiveEvent event) {
            CognitiveEventType type = event.event();
            Instant at = event.timestamp() == null ? Instant.now() : event.timestamp();
            if (type == CognitiveEventType.THINKING_TOKEN || type == CognitiveEventType.ANSWER_TOKEN) {
                String kind = type == CognitiveEventType.THINKING_TOKEN ? "MYŚLENIE" : "ODPOWIEDŹ";
                if (stream == null || !stream.kind.equals(kind)) {
                    closeStream();
                    stream = new Entry(kind, at, "");
                    streamText = new StringBuilder();
                    entries.add(stream);
                }
                streamText.append(event.message() == null ? "" : event.message());
                stream.end = at;
                return;
            }
            closeStream();
            if (SKIPPED.contains(type)) return;
            Map<String, Object> metadata = event.metadata();
            switch (type) {
                case TOOL_EXECUTION_STARTED -> {
                    // Sub-steps of a one-call tool (pc__spotify_play...) are shown, but timed only once, by their parent.
                    Entry tool = new Entry(runningTools.isEmpty() ? "NARZĘDZIE" : "↳ krok", at, metadata.get("tool") + "." + metadata.get("operation") + " "
                            + compact(metadata.get("arguments")));
                    runningTools.push(tool);
                    entries.add(tool);
                }
                case TOOL_EXECUTION_FINISHED -> {
                    Entry tool = runningTools.isEmpty() ? new Entry("NARZĘDZIE", at, String.valueOf(metadata.get("tool"))) : runningTools.pop();
                    if (!entries.contains(tool)) entries.add(tool);
                    tool.end = at;
                    // A user's mail (and attachments) never goes into diagnostic files; the status line is enough to diagnose.
                    boolean privateData = PRIVATE_TOOLS.contains(String.valueOf(metadata.get("tool")));
                    tool.text += "\n→ " + (Boolean.TRUE.equals(metadata.get("success")) ? "OK" : "BŁĄD " + metadata.getOrDefault("errorCode", ""))
                            + ": " + oneLine(String.valueOf(metadata.getOrDefault("message", "")), maxFieldChars)
                            + (metadata.containsKey("data") ? "\n" + (privateData ? "[treść poczty pominięta]" : compact(metadata.get("data"))) : "");
                }
                default -> {
                    Map<String, Object> details = new LinkedHashMap<>(metadata);
                    details.remove("requestId");
                    details.remove("conversationId");
                    String text = oneLine(String.valueOf(event.message() == null ? "" : event.message()), maxFieldChars)
                            + (details.isEmpty() ? "" : "\n" + compact(details));
                    entries.add(new Entry(type.name(), at, text));
                }
            }
        }

        synchronized void close() {
            closeStream();
        }

        private void closeStream() {
            if (stream != null) {
                String text = streamText.toString().strip();
                stream.text = "(" + text.length() + " znaków)\n" + (text.length() <= maxFieldChars * 4 ? text : text.substring(0, maxFieldChars * 4) + "…");
                stream = null;
                streamText = null;
            }
        }
    }
}
