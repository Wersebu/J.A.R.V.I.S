package com.jarvis.tools.runtime;

import com.jarvis.tools.ToolResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Carries out pc__spotify_play: the model decides what to play and where (source, query, pick, device), and
 * Core runs the known procedure in one tool call instead of the model deliberating before each of ~10 steps.
 * Every sub-call is a real pc SPOTIFY/OPEN call recorded in the loop's steps and checked by
 * {@link SpotifyPlaybackPolicy}: the workflow is read, the URI comes from this request's results, the device is
 * the local PC or one the user asked for, and success requires observed playback.
 */
final class SpotifyAutopilot {
    static final String OPERATION = "SPOTIFY_PLAY";
    private static final int READINESS_READS = 3; // devices after OPEN; the policy allows four reads in total
    private static final int VERIFY_READS = 2;

    interface Executor {
        ToolResult run(ToolAction action, int step);
    }

    interface Pause {
        void pause(long millis);
    }

    private final ToolCallingRequest request;
    private final Map<String, Object> arguments;
    private final Executor executor;
    private final Pause pause;
    private final long pollMillis;
    private final List<ToolRuntimeStep> steps;
    private final int stepNumber;

    private SpotifyAutopilot(ToolCallingRequest request, Map<String, Object> arguments, List<ToolRuntimeStep> steps,
                             int stepNumber, Executor executor, Pause pause, long pollMillis) {
        this.request = request;
        this.arguments = arguments;
        this.steps = steps;
        this.stepNumber = stepNumber;
        this.executor = executor;
        this.pause = pause;
        this.pollMillis = pollMillis;
    }

    static boolean handles(ToolAction action) {
        return "pc".equalsIgnoreCase(action.tool()) && OPERATION.equalsIgnoreCase(action.operation());
    }

    /**
     * Runs one pc__spotify_play call. Sub-calls are appended to {@code steps} as they execute.
     *
     * @return a summary result for the model: success only when playback was observed
     */
    static ToolResult play(ToolCallingRequest request, ToolAction action, List<ToolRuntimeStep> steps, int stepNumber,
                           Executor executor, Pause pause, long pollMillis) {
        return new SpotifyAutopilot(request, action.arguments(), steps, stepNumber, executor, pause, pollMillis).play();
    }

    private ToolResult play() {
        String source = text("source").toLowerCase(Locale.ROOT);
        if (source.isBlank()) source = text("query").isBlank() ? "liked" : "search";
        if (!source.equals("liked") && !source.equals("search")) return failure("BAD_ARGUMENT", "source must be liked or search.");
        if (source.equals("search") && text("query").isBlank()) return failure("BAD_ARGUMENT", "source=search needs query.");
        if (SpotifyPlaybackPolicy.played(steps) != null)
            return failure("ALREADY_PLAYED", "Playback was already sent in this request; do not repeat it. Report the result.");

        if (!SpotifyPlaybackPolicy.workflowRead(steps) && !readWorkflow())
            return failure("WORKFLOW_UNAVAILABLE", "The Spotify procedure (workflows/Spotify.md) could not be found or read in the knowledge vault.");
        call("pc", "SPOTIFY", Map.of("action", "status"));
        if (!SpotifyPlaybackPolicy.connected(steps))
            return failure("NOT_CONNECTED", "Spotify is not connected. The user must connect Spotify first (never connect automatically).");

        Map<?, ?> device = device();
        if (device == null) return failure("NO_DEVICE", deviceProblem());

        ToolResult listed = source.equals("liked")
                ? call("pc", "SPOTIFY", Map.of("action", "liked", "limit", 50))
                : call("pc", "SPOTIFY", Map.of("action", "search", "query", text("query"),
                        "type", text("type").isBlank() ? "track" : text("type"), "limit", 10));
        if (!listed.success()) return failure("SOURCE_FAILED", "Reading " + source + " failed: " + listed.message());
        List<String> candidates = candidates(listed);
        if (candidates.isEmpty()) return failure("NOTHING_FOUND", source.equals("liked")
                ? "No playable liked songs." : "Search for \"" + text("query") + "\" returned nothing playable.");
        String chosen = text("pick").equalsIgnoreCase("first") || (source.equals("search") && !text("pick").equalsIgnoreCase("random"))
                ? candidates.getFirst() : candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));

        Map<String, Object> playArguments = new LinkedHashMap<>(Map.of("action", "play", "target", chosen, "deviceId", device.get("id")));
        ToolAction play = SpotifyPlaybackPolicy.prepare(request, action("pc", "SPOTIFY", playArguments), steps);
        String blocked = SpotifyPlaybackPolicy.guard(request, play, steps);
        if (!blocked.isBlank()) return failure("BLOCKED", blocked);
        run(play);
        for (int read = 0; read < VERIFY_READS && !SpotifyPlaybackPolicy.confirmed(request, steps); read++) {
            pause.pause(pollMillis);
            call("pc", "SPOTIFY", Map.of("action", "current"));
        }
        return summary();
    }

    /** The local PC by default (opening the app when needed); another device only when the user asked for it. */
    private Map<?, ?> device() {
        String wanted = text("device");
        call("pc", "SPOTIFY", Map.of("action", "devices"));
        if (!SpotifyPlaybackPolicy.isLocalDeviceWord(wanted)) return SpotifyPlaybackPolicy.requestedDevice(request, steps, wanted);
        if (SpotifyPlaybackPolicy.localDevice(steps) == null) {
            ToolAction open = action("pc", "OPEN", Map.of("target", "spotify"));
            if (SpotifyPlaybackPolicy.guard(request, open, steps).isBlank()) run(open);
            for (int read = 0; read < READINESS_READS && SpotifyPlaybackPolicy.localDevice(steps) == null; read++) {
                pause.pause(pollMillis); // a just-launched app needs a moment to register in Spotify Connect
                call("pc", "SPOTIFY", Map.of("action", "devices"));
            }
        }
        return SpotifyPlaybackPolicy.localDevice(steps);
    }

    private String deviceProblem() {
        String wanted = text("device");
        String visible = String.join(", ", SpotifyPlaybackPolicy.visibleDeviceNames(steps));
        if (SpotifyPlaybackPolicy.isLocalDeviceWord(wanted))
            return "This PC is not visible in Spotify Connect even after opening the app. Visible devices: [" + visible + "]. "
                    + "deviceName in Windows config/spotify.json must match the Spotify device name.";
        return "Device \"" + wanted + "\" is not available to the Spotify Web API, or the user did not ask for it. Visible devices: ["
                + visible + "]. Devices found only on the local network (e.g. a Cast TV) appear here once Spotify is "
                + "active on them; the user can select it once in the Spotify app.";
    }

    private List<String> candidates(ToolResult listed) {
        String type = text("source").equalsIgnoreCase("search") || !text("query").isBlank()
                ? (text("type").isBlank() ? "track" : text("type").split(",")[0].strip()) : "track";
        Map<String, Map<?, ?>> allowed = SpotifyPlaybackPolicy.sourceItems(request, steps);
        Map<String, Map<?, ?>> found = new LinkedHashMap<>();
        SpotifyPlaybackPolicy.collect(listed.data(), found);
        return found.keySet().stream().filter(allowed::containsKey).filter(uri -> uri.startsWith("spotify:" + type + ":")).toList();
    }

    private ToolResult summary() {
        boolean confirmed = SpotifyPlaybackPolicy.confirmed(request, steps);
        Map<String, Object> data = new LinkedHashMap<>(SpotifyPlaybackPolicy.playbackFacts(request, steps));
        data.put("confirmed", confirmed);
        String message = confirmed
                ? "Playback confirmed: " + data.get("title") + (data.containsKey("artists") ? " — " + data.get("artists") : "") + " on " + data.get("device")
                : SpotifyPlaybackPolicy.answer(request, steps);
        return result(confirmed, message, data, confirmed ? "" : "PLAYBACK_NOT_CONFIRMED");
    }

    private boolean readWorkflow() {
        call("knowledge", "FIND_WORKFLOW", Map.of("query", "Spotify"));
        String path = SpotifyPlaybackPolicy.workflowPaths(steps).stream().findFirst().orElse("");
        if (path.isBlank()) return false;
        ToolResult first = call("knowledge", "READ_WORKFLOW", Map.of("path", path));
        int parts = first.success() && first.data().get("parts") instanceof Number n ? Math.min(n.intValue(), 100) : 1;
        for (int part = 2; part <= parts; part++) call("knowledge", "READ_WORKFLOW", Map.of("path", path, "part", part));
        return SpotifyPlaybackPolicy.workflowRead(steps);
    }

    private ToolResult call(String tool, String operation, Map<String, Object> arguments) {
        return run(action(tool, operation, arguments));
    }

    private static ToolAction action(String tool, String operation, Map<String, Object> arguments) {
        return new ToolAction("TOOL_CALL", tool, operation, arguments, "pc__spotify_play sub-step", "");
    }

    private ToolResult run(ToolAction action) {
        ToolResult result = executor.run(action, stepNumber);
        steps.add(new ToolRuntimeStep(stepNumber, "TOOL_CALL", action.tool(), action.operation(), result.success() ? "OK" : "FAILED",
                result, action.arguments()));
        return result;
    }

    private ToolResult failure(String code, String message) {
        Map<String, Object> data = new LinkedHashMap<>(Map.of("confirmed", false));
        data.put("visibleDevices", SpotifyPlaybackPolicy.visibleDeviceNames(steps));
        return result(false, message, data, code);
    }

    private ToolResult result(boolean success, String message, Map<String, Object> data, String code) {
        return new ToolResult(success, "pc", OPERATION, request.requestId(), request.conversationId(), success, List.of(), message,
                data, code, success ? "" : message, false, "");
    }

    private String text(String key) {
        return Objects.toString(arguments.get(key), "").strip();
    }
}
