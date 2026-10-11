package com.jarvis.tools.runtime;

import com.jarvis.tools.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Carries out a generic Spotify playback request ("odpal coś", "puść coś z polubionych") without asking the
 * model before every step. The procedure is fully known in advance, so a local model deliberating over each
 * call only adds minutes. Every call still goes through {@link SpotifyPlaybackPolicy}: the workflow is read,
 * the source URI comes from this request's liked songs, the device is the identified local PC, and success
 * requires observed playback.
 */
final class SpotifyAutopilot {
    private static final int READINESS_READS = 3; // devices after OPEN; the policy allows four reads in total
    private static final int VERIFY_READS = 2;

    interface Executor {
        ToolResult run(ToolAction action, int step);
    }

    interface Pause {
        void pause(long millis);
    }

    private final ToolCallingRequest request;
    private final Executor executor;
    private final Pause pause;
    private final long pollMillis;
    private final List<ToolRuntimeStep> steps = new ArrayList<>();

    private SpotifyAutopilot(ToolCallingRequest request, Executor executor, Pause pause, long pollMillis) {
        this.request = request;
        this.executor = executor;
        this.pause = pause;
        this.pollMillis = pollMillis;
    }

    /**
     * Runs the procedure.
     *
     * @return the executed steps, or empty when the request cannot be carried deterministically (no readable
     *         workflow, no liked songs, a blocked call) and the model loop must take over from scratch
     */
    static Optional<List<ToolRuntimeStep>> run(ToolCallingRequest request, Executor executor, Pause pause, long pollMillis) {
        return new SpotifyAutopilot(request, executor, pause, pollMillis).run();
    }

    private Optional<List<ToolRuntimeStep>> run() {
        if (!readWorkflow()) return Optional.empty();
        call("pc", "SPOTIFY", Map.of("action", "status"));
        // Not connected: the policy's answer tells the user to log in; connecting is never automatic.
        if (!SpotifyPlaybackPolicy.connected(steps)) return Optional.of(steps);
        call("pc", "SPOTIFY", Map.of("action", "devices"));
        if (SpotifyPlaybackPolicy.localDevice(steps) == null) {
            if (!blocked(spotifyOpen())) call("pc", "OPEN", Map.of("target", "spotify"));
            for (int read = 0; read < READINESS_READS && SpotifyPlaybackPolicy.localDevice(steps) == null; read++) {
                pause.pause(pollMillis); // a just-launched app needs a moment to register in Spotify Connect
                call("pc", "SPOTIFY", Map.of("action", "devices"));
            }
            if (SpotifyPlaybackPolicy.localDevice(steps) == null) return Optional.of(steps);
        }
        call("pc", "SPOTIFY", Map.of("action", "liked", "limit", 50));
        List<String> tracks = SpotifyPlaybackPolicy.sourceItems(request, steps).keySet().stream()
                .filter(uri -> uri.startsWith("spotify:track:")).toList();
        if (tracks.isEmpty()) return Optional.empty(); // nothing liked to choose from: let the model search
        String chosen = tracks.get(ThreadLocalRandom.current().nextInt(tracks.size()));
        ToolAction play = SpotifyPlaybackPolicy.prepare(request, action("pc", "SPOTIFY", Map.of("action", "play", "target", chosen)), steps);
        if (blocked(play)) return Optional.empty();
        run(play);
        for (int read = 0; read < VERIFY_READS && !SpotifyPlaybackPolicy.confirmed(request, steps); read++) {
            pause.pause(pollMillis);
            call("pc", "SPOTIFY", Map.of("action", "current"));
        }
        return Optional.of(steps);
    }

    private ToolAction spotifyOpen() {
        return action("pc", "OPEN", Map.of("target", "spotify"));
    }

    private boolean blocked(ToolAction action) {
        return !SpotifyPlaybackPolicy.guard(request, action, steps).isBlank();
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
        return new ToolAction("TOOL_CALL", tool, operation, arguments, "Spotify procedure carried out by Core", "");
    }

    private ToolResult run(ToolAction action) {
        String tool = action.tool(), operation = action.operation();
        ToolResult result = executor.run(action, steps.size() + 1);
        steps.add(new ToolRuntimeStep(steps.size() + 1, "TOOL_CALL", tool, operation, result.success() ? "OK" : "FAILED",
                result, action.arguments()));
        return result;
    }
}
