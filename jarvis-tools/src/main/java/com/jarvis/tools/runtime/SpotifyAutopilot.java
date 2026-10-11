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
    static final String CONTROL = "SPOTIFY_CONTROL";
    private static final java.util.Set<String> CONTROLS =
            java.util.Set.of("now", "like", "next", "previous", "pause", "resume", "volume", "shuffle", "repeat");
    private static final java.util.Set<String> SOURCES = java.util.Set.of("liked", "search", "current", "random", "library");
    private static final int CONTROL_CHECKS = 3;
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

    /**
     * A model that reaches for low-level pc__spotify to steer playback (next, pause, volume... or an invented
     * "transfer") gets the one-call tool with the same meaning, instead of a multi-turn detour or an
     * "unsupported action" error. Reads, library edits and validated plays of a chosen URI pass unchanged.
     */
    static ToolAction redirect(ToolAction action) {
        if (!"pc".equalsIgnoreCase(action.tool()) || !"SPOTIFY".equalsIgnoreCase(action.operation())) return action;
        Map<String, Object> args = action.arguments();
        String sub = Objects.toString(args.get("action"), "").strip().toLowerCase(Locale.ROOT);
        String target = Objects.toString(args.get("target"), "").strip();
        Map<String, Object> mapped = new LinkedHashMap<>();
        String operation;
        if (java.util.Set.of("transfer", "move").contains(sub) || sub.equals("play") && Boolean.parseBoolean(Objects.toString(args.get("transfer"), ""))) {
            operation = OPERATION;
            mapped.put("source", "current");
            Object device = args.getOrDefault("deviceName", args.get("deviceId"));
            if (device != null) mapped.put("device", device);
        } else if (java.util.Set.of("pause", "next", "previous", "volume", "shuffle", "repeat").contains(sub) || sub.equals("play") && target.isBlank()) {
            operation = CONTROL;
            mapped.put("action", sub.equals("play") ? "resume" : sub);
            for (String key : List.of("value", "state")) if (args.get(key) != null) mapped.put(key, args.get(key));
        } else {
            return action;
        }
        return new ToolAction(action.action(), "pc", operation, mapped, action.reason(), action.answer());
    }

    static boolean handles(ToolAction action) {
        return "pc".equalsIgnoreCase(action.tool())
                && (OPERATION.equalsIgnoreCase(action.operation()) || CONTROL.equalsIgnoreCase(action.operation()));
    }

    /**
     * Runs one pc__spotify_play call. Sub-calls are appended to {@code steps} as they execute.
     *
     * @return a summary result for the model: success only when playback was observed
     */
    static ToolResult play(ToolCallingRequest request, ToolAction action, List<ToolRuntimeStep> steps, int stepNumber,
                           Executor executor, Pause pause, long pollMillis) {
        SpotifyAutopilot pilot = new SpotifyAutopilot(request, action.arguments(), steps, stepNumber, executor, pause, pollMillis);
        return CONTROL.equalsIgnoreCase(action.operation()) ? pilot.control() : pilot.play();
    }

    /**
     * pc__spotify_control: next/previous/pause/resume/volume/shuffle/repeat on whichever device is playing now
     * (the PC, or e.g. the TV the user moved it to), then a short check that the player actually changed.
     * No workflow read and no device discovery: it acts on the live session the user already has.
     */
    private ToolResult control() {
        String act = text("action").toLowerCase(Locale.ROOT);
        if (!CONTROLS.contains(act)) return failure(CONTROL, "BAD_ARGUMENT", "action must be one of " + CONTROLS + ".");
        if (act.equals("volume") && text("value").isBlank()) return failure(CONTROL, "BAD_ARGUMENT", "volume needs value 0-100.");
        if ((act.equals("shuffle") || act.equals("repeat")) && text("state").isBlank())
            return failure(CONTROL, "BAD_ARGUMENT", act + " needs state (shuffle: true/false; repeat: off/track/context).");
        Map<?, ?> before = playback(call("pc", "SPOTIFY", Map.of("action", "current")));
        if (act.equals("now")) return nowPlaying(before);
        if (act.equals("like")) return like(before);
        String deviceId = Objects.toString(before.get("deviceId"), "");
        if (deviceId.isBlank()) {
            if (!act.equals("resume")) return failure(CONTROL, "NOTHING_PLAYING",
                    "No active Spotify session to control. Start something with pc__spotify_play first.");
            call("pc", "SPOTIFY", Map.of("action", "devices"));
            Map<?, ?> local = SpotifyPlaybackPolicy.localDevice(steps);
            if (local == null) return failure(CONTROL, "NO_DEVICE", "Nothing to resume and this PC is not visible in Spotify Connect.");
            deviceId = String.valueOf(local.get("id"));
        }
        Map<String, Object> command = new LinkedHashMap<>(Map.of("action", act.equals("resume") ? "play" : act, "deviceId", deviceId));
        if (!text("value").isBlank()) command.put("value", text("value"));
        if (!text("state").isBlank()) command.put("state", text("state"));
        ToolResult sent = run(action("pc", "SPOTIFY", command));
        if (!sent.success()) return failure(CONTROL, "COMMAND_FAILED", "Spotify rejected " + act + ": " + sent.message());

        Map<?, ?> after = playback(sent.data().get("observedPlayback"));
        for (int check = 0; check < CONTROL_CHECKS && !changed(act, before, after); check++) {
            pause.pause(Math.max(pollMillis / 2, 0)); // the player updates asynchronously
            after = playback(call("pc", "SPOTIFY", Map.of("action", "current")));
        }
        boolean confirmed = changed(act, before, after);
        Map<String, Object> data = new LinkedHashMap<>(Map.of("action", act, "confirmed", confirmed));
        Object item = after.get("item");
        if (item instanceof Map<?, ?> track && track.get("name") instanceof String name && !name.isBlank()) {
            data.put("title", name);
            if (track.get("artists") instanceof List<?> artists && !artists.isEmpty())
                data.put("artists", String.join(", ", artists.stream().map(String::valueOf).toList()));
        }
        data.put("isPlaying", Boolean.TRUE.equals(after.get("isPlaying")));
        data.put("device", Objects.toString(after.get("deviceName"), Objects.toString(before.get("deviceName"), "")));
        String message = confirmed
                ? "Spotify " + act + " done on " + data.get("device") + (data.containsKey("title")
                        ? ". Now: " + data.get("title") + (data.containsKey("artists") ? " — " + data.get("artists") : "") : "") + "."
                : "Spotify accepted " + act + " but the player did not show the change yet.";
        return result(CONTROL, confirmed, message, data, confirmed ? "" : "CONTROL_NOT_CONFIRMED");
    }

    /** "Co to za piosenka?": a plain read of the live player, no command. */
    private ToolResult nowPlaying(Map<?, ?> state) {
        Map<String, Object> data = trackFacts(state);
        if (!data.containsKey("title")) {
            data.put("isPlaying", false);
            return result(CONTROL, true, "Nothing is playing on Spotify right now.", data, "");
        }
        return result(CONTROL, true, (Boolean.TRUE.equals(data.get("isPlaying")) ? "Now playing: " : "Paused: ") + data.get("title")
                + (data.containsKey("artists") ? " — " + data.get("artists") : "") + " on " + data.get("device") + ".", data, "");
    }

    /** "Polub to": saves the track that is playing now to Liked Songs. */
    private ToolResult like(Map<?, ?> state) {
        String uri = trackUri(state);
        if (!uri.startsWith("spotify:track:") && !uri.startsWith("spotify:episode:"))
            return failure(CONTROL, "NOTHING_PLAYING", "Nothing is playing, so there is no track to like.");
        ToolResult saved = run(action("pc", "SPOTIFY", Map.of("action", "save", "target", uri)));
        Map<String, Object> data = trackFacts(state);
        data.put("liked", saved.success());
        return saved.success()
                ? result(CONTROL, true, "Saved to Liked Songs: " + data.get("title") + ".", data, "")
                : failure(CONTROL, "COMMAND_FAILED", "Spotify did not save the track: " + saved.message());
    }

    private static Map<String, Object> trackFacts(Map<?, ?> state) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (state.get("item") instanceof Map<?, ?> track && track.get("name") instanceof String name && !name.isBlank()) {
            data.put("title", name);
            if (track.get("artists") instanceof List<?> artists && !artists.isEmpty())
                data.put("artists", String.join(", ", artists.stream().map(String::valueOf).toList()));
            if (track.get("album") != null) data.put("album", String.valueOf(track.get("album")));
            data.put("uri", Objects.toString(track.get("uri"), ""));
        }
        data.put("isPlaying", Boolean.TRUE.equals(state.get("isPlaying")));
        data.put("device", Objects.toString(state.get("deviceName"), ""));
        return data;
    }

    /** Whether the observed player reflects the command; settings commands are confirmed by their acceptance. */
    private static boolean changed(String act, Map<?, ?> before, Map<?, ?> after) {
        Object playing = after.get("isPlaying");
        return switch (act) {
            case "pause" -> Boolean.FALSE.equals(playing);
            case "resume" -> Boolean.TRUE.equals(playing);
            case "next" -> Boolean.TRUE.equals(playing) && !Objects.equals(trackUri(before), trackUri(after)) && !trackUri(after).isBlank();
            case "previous" -> Boolean.TRUE.equals(playing) && (!Objects.equals(trackUri(before), trackUri(after))
                    || after.get("progressMs") instanceof Number now && before.get("progressMs") instanceof Number then
                    && now.longValue() < then.longValue());
            default -> true;
        };
    }

    private static String trackUri(Map<?, ?> state) {
        return state.get("item") instanceof Map<?, ?> item ? Objects.toString(item.get("uri"), "") : "";
    }

    private static Map<?, ?> playback(Object value) {
        if (value instanceof ToolResult result) value = result.success() ? result.data().get("playback") : null;
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    private ToolResult play() {
        String source = text("source").toLowerCase(Locale.ROOT);
        if (source.isBlank()) source = text("query").isBlank() ? "liked" : "search";
        if (!SOURCES.contains(source)) return failure("BAD_ARGUMENT", "source must be one of " + SOURCES + ".");
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
        // "Odpal to na telewizorze": the user names another device and points at what is playing - that is a move.
        if (source.equals("liked") && device != SpotifyPlaybackPolicy.localDevice(steps) && SpotifyPlaybackPolicy.refersToCurrent(request))
            source = "current";

        if (source.equals("current")) return moveCurrent(device);
        String type = switch (source) {
            case "search" -> text("type").isBlank() ? "track" : text("type").split(",")[0].strip().toLowerCase(Locale.ROOT);
            case "library" -> "playlist";
            default -> "track";
        };
        ToolResult listed = switch (source) {
            case "liked" -> call("pc", "SPOTIFY", Map.of("action", "liked", "limit", 50));
            case "library" -> call("pc", "SPOTIFY", Map.of("action", "library", "type", "playlist", "limit", 50));
            // Spotify has no "random track" endpoint: a random letter at a random depth of the catalogue.
            case "random" -> call("pc", "SPOTIFY", Map.of("action", "search", "type", "track", "limit", 10,
                    "query", String.valueOf((char) ('a' + ThreadLocalRandom.current().nextInt(26))),
                    "offset", ThreadLocalRandom.current().nextInt(0, 500)));
            default -> call("pc", "SPOTIFY", Map.of("action", "search", "query", text("query"), "type", type, "limit", 10));
        };
        if (!listed.success()) return failure("SOURCE_FAILED", "Reading " + source + " failed: " + listed.message());
        List<String> candidates = candidates(listed, type);
        if (candidates.isEmpty()) return failure("NOTHING_FOUND", switch (source) {
            case "liked" -> "No playable liked songs.";
            case "library" -> "None of the user's playlists matches \"" + text("query") + "\".";
            default -> "Search for \"" + text("query") + "\"" + (text("owner").isBlank() ? "" : " by " + text("owner")) + " returned nothing playable.";
        });
        boolean first = text("pick").equalsIgnoreCase("first") || !text("pick").equalsIgnoreCase("random")
                && (source.equals("search") || source.equals("library") && !text("query").isBlank());
        String chosen = first ? candidates.getFirst() : candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
        if (text("mode").equalsIgnoreCase("queue")) return enqueue(chosen);

        Map<String, Object> playArguments = new LinkedHashMap<>(Map.of("action", "play", "target", chosen, "deviceId", device.get("id")));
        return playAndVerify(new LinkedHashMap<>(SpotifyPlaybackPolicy.prepare(request, action("pc", "SPOTIFY", playArguments), steps).arguments()));
    }

    /** Moves what is playing now to the chosen device: same track, same context (queue), same position. */
    private ToolResult moveCurrent(Map<?, ?> device) {
        ToolResult now = call("pc", "SPOTIFY", Map.of("action", "current"));
        Object playback = now.success() ? now.data().get("playback") : null;
        Map<?, ?> state = playback instanceof Map<?, ?> map ? map : Map.of();
        String uri = state.get("item") instanceof Map<?, ?> item ? Objects.toString(item.get("uri"), "") : "";
        if (!uri.startsWith("spotify:track:") && !uri.startsWith("spotify:episode:"))
            return failure("NOTHING_PLAYING", "Nothing is playing on Spotify right now, so there is nothing to move. Use source=liked or search.");
        // Spotify's transfer carries the exact session over (track, position, queue, smart shuffle); the current
        // track is passed only so the move is verified against what was actually playing.
        return playAndVerify(new LinkedHashMap<>(Map.of("action", "play", "target", uri, "deviceId", device.get("id"), "transfer", "true")));
    }

    private ToolResult playAndVerify(Map<String, Object> playArguments) {
        ToolAction play = action("pc", "SPOTIFY", playArguments);
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
        Map<?, ?> local = SpotifyPlaybackPolicy.localDevice(steps);
        boolean thisPc = SpotifyPlaybackPolicy.isLocalDeviceWord(wanted) || local != null
                && (wanted.equals(local.get("id")) || wanted.equalsIgnoreCase(String.valueOf(local.get("name"))));
        if (!thisPc) return SpotifyPlaybackPolicy.requestedDevice(request, steps, wanted);
        // The model left the device out, but the user named one ("na telewizorze"): their words decide.
        Map<?, ?> asked = wanted.isBlank() ? SpotifyPlaybackPolicy.userRequestedDevice(request, steps) : null;
        if (asked != null) return asked;
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

    /** Real results of the read just made, of the wanted type, narrowed by playlist owner or (library) name. */
    private List<String> candidates(ToolResult listed, String type) {
        Map<String, Map<?, ?>> allowed = SpotifyPlaybackPolicy.sourceItems(request, steps);
        Map<String, Map<?, ?>> found = new LinkedHashMap<>();
        SpotifyPlaybackPolicy.collect(listed.data(), found);
        String owner = SpotifyPlaybackPolicy.normalize(text("owner"));
        String name = text("source").equalsIgnoreCase("library") ? SpotifyPlaybackPolicy.normalize(text("query")) : "";
        List<Map.Entry<String, Map<?, ?>>> matching = found.entrySet().stream()
                .filter(e -> allowed.containsKey(e.getKey()) && e.getKey().startsWith("spotify:" + type + ":"))
                .filter(e -> owner.isBlank() || SpotifyPlaybackPolicy.normalize(Objects.toString(e.getValue().get("owner"), "")).contains(owner))
                .filter(e -> name.isBlank() || SpotifyPlaybackPolicy.normalize(Objects.toString(e.getValue().get("name"), "")).contains(name))
                .toList();
        if (!text("source").equalsIgnoreCase("search") || text("query").isBlank()) return matching.stream().map(Map.Entry::getKey).toList();
        // Spotify's first hit is often the artist's popular track, not the one asked for ("ogórek cypisa" gave
        // "Niegrzeczna Studentka"): prefer results whose title/artists/album carry the most words of the request.
        List<String> wanted = words(text("query"));
        return matching.stream()
                .sorted(java.util.Comparator.comparingInt((Map.Entry<String, Map<?, ?>> e) -> -overlap(wanted, e.getValue())))
                .map(Map.Entry::getKey).toList();
    }

    private static int overlap(List<String> wanted, Map<?, ?> item) {
        List<String> have = words(item.get("name") + " " + item.get("artists") + " " + item.get("album"));
        int score = 0;
        for (String word : wanted) {
            String stem = word.substring(0, Math.min(5, word.length()));
            if (have.stream().anyMatch(h -> h.startsWith(stem) || h.length() >= 4 && word.startsWith(h.substring(0, Math.min(5, h.length())))))
                score++;
        }
        return score;
    }

    private static List<String> words(String text) {
        return java.util.Arrays.stream(SpotifyPlaybackPolicy.normalize(String.valueOf(text)).split("[^a-z0-9]+"))
                .filter(word -> word.length() >= 3).toList();
    }

    /** mode=queue: adds the chosen track after the current one instead of interrupting playback. */
    private ToolResult enqueue(String uri) {
        if (!uri.startsWith("spotify:track:") && !uri.startsWith("spotify:episode:"))
            return failure("BAD_ARGUMENT", "Only tracks or episodes can be added to the queue; use type=track.");
        Map<?, ?> state = playback(call("pc", "SPOTIFY", Map.of("action", "current")));
        String deviceId = Objects.toString(state.get("deviceId"), "");
        if (deviceId.isBlank()) return failure("NOTHING_PLAYING", "Nothing is playing, so there is no queue to add to. Play it instead.");
        ToolResult queued = run(action("pc", "SPOTIFY", Map.of("action", "enqueue", "target", uri, "deviceId", deviceId)));
        Map<?, ?> item = SpotifyPlaybackPolicy.sourceItems(request, steps).getOrDefault(uri, Map.of());
        Map<String, Object> data = new LinkedHashMap<>(Map.of("queued", queued.success(), "uri", uri,
                "title", Objects.toString(item.get("name"), ""), "device", Objects.toString(state.get("deviceName"), "")));
        return queued.success()
                ? result(true, "Added to the queue: " + data.get("title") + ".", data, "")
                : failure("COMMAND_FAILED", "Spotify did not add it to the queue: " + queued.message());
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
        return failure(OPERATION, code, message);
    }

    private ToolResult failure(String operation, String code, String message) {
        Map<String, Object> data = new LinkedHashMap<>(Map.of("confirmed", false));
        data.put("visibleDevices", SpotifyPlaybackPolicy.visibleDeviceNames(steps));
        return result(operation, false, message, data, code);
    }

    private ToolResult result(boolean success, String message, Map<String, Object> data, String code) {
        return result(OPERATION, success, message, data, code);
    }

    private ToolResult result(String operation, boolean success, String message, Map<String, Object> data, String code) {
        return new ToolResult(success, "pc", operation, request.requestId(), request.conversationId(), success, List.of(), message,
                data, code, success ? "" : message, false, "");
    }

    private String text(String key) {
        return Objects.toString(arguments.get(key), "").strip();
    }
}
