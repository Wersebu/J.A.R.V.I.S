package com.jarvis.tools.runtime;

import com.jarvis.tools.workflow.CompletionAssessment;
import java.text.Normalizer;
import java.util.*;

/** Per-request desktop playback procedure. Decisions depend on executed calls, never model prose. */
final class SpotifyPlaybackPolicy {
    static final String BLOCKED = "SPOTIFY_PROCEDURE_REQUIRED";
    static final String WORKFLOW = "workflows/Spotify.md";
    private static final int MAX_OBSERVATIONS = 4; // initial read plus three bounded readiness observations

    static boolean applies(ToolCallingRequest request) {
        String text = normalize(request.userMessage());
        String domain = text + " " + normalize(Objects.toString(request.goal(), "")) + " " + normalize(request.conversationContext());
        if (!domain.contains("spotify") || text.contains("web player")) return false;
        text = text.replaceAll("nie (uruchamiaj|odtwarzaj|wlaczaj|puszczaj)[^.!?]*", "")
                .replaceAll("(do not|don't|without) (play|start)[^.!?]*", "");
        return java.util.regex.Pattern.compile("\\b(play|listen)\\b|\\b(odpal|pusc|puszcz|wlacz|zagraj|odtworz)\\w*")
                .matcher(text).find();
    }

    static String guard(ToolCallingRequest request, ToolAction action, List<ToolRuntimeStep> steps) {
        String tool = action.tool().toLowerCase(Locale.ROOT);
        String op = action.operation().toUpperCase(Locale.ROOT);
        if (tool.equals("knowledge") && Set.of("READ_WORKFLOW", "READ_DOCUMENT", "FIND_WORKFLOW", "SEARCH_CONTENT",
                "SEARCH_DOCUMENT", "LIST_TREE", "LIST_FOLDER").contains(op)) return "";
        if (tool.equals("plan") || tool.equals("system") && op.equals("NOTIFY_USER")) return "";
        if (!workflowRead(steps)) return "Read the complete " + WORKFLOW
                + " using knowledge__read_workflow (or read_document) before executing Spotify. Do not infer a procedure from memory.";
        if (!tool.equals("pc")) return "Use desktop pc__spotify; do not switch to browser/Web Player or other execution tools.";
        if (op.equals("OPEN")) {
            if (!"spotify".equalsIgnoreCase(text(action.arguments(), "target")))
                return "Only pc__open(target=spotify) may launch the desktop app. Do not open track URLs or Web Player.";
            if (!connected(steps) || latest(steps, "devices") == null)
                return "First read status, then devices. Opening the app does not establish Spotify authorization.";
            if (localDevice(steps) != null) return "The local PC is already available. Use search and play; opening again is not needed.";
            return "";
        }
        if (!op.equals("SPOTIFY")) return "Use pc__spotify, not MEDIA, shell, browser, or URL-based playback.";
        String sub = text(action.arguments(), "action").toLowerCase(Locale.ROOT);
        if (!Set.of("status", "devices", "search", "liked", "library", "play", "current").contains(sub))
            return "This playback request does not authorize connect/disconnect or unrelated mutations.";
        if (Set.of("status", "devices", "current").contains(sub) && attempts(steps, sub) >= MAX_OBSERVATIONS)
            return "Spotify observation budget exhausted. Report playback as unconfirmed; do not loop.";
        if (sub.equals("status")) return "";
        if (!connected(steps)) return "Read status first. If connected=false, report that login is required; do not automatically connect.";
        if (sub.equals("devices") || sub.equals("liked") || sub.equals("library")) return "";
        if (sub.equals("current")) return played(steps) == null ? "Issue a validated play before verifying it with current." : "";
        Map<?, ?> device = localDevice(steps);
        if (device == null) return "Read devices and identify exactly one local=true Computer. If absent, open the desktop app once, "
                + "then read devices again. Do not select another computer, phone or Web Player. A Windows client with local-device metadata is required.";
        if (sub.equals("search")) return "";
        if (played(steps) != null) return "A play was already attempted. Do not duplicate a possibly accepted mutation; verify with current or report partial completion.";
        String uri = text(action.arguments(), "target");
        if (!sourceItems(request, steps).containsKey(uri)) return "Play target must be an exact URI from " + (fromLiked(request) ? "liked" : "search/library") + " in this request. "
                + "Read that source and select a real result; never use a URI from model memory or an earlier assistant message. "
                + "Invalid URI is not an authentication failure: do not connect.";
        if (!Objects.equals(device.get("id"), action.arguments().get("deviceId")))
            return "Specify deviceId of the local=true Computer from the latest devices result.";
        return "";
    }

    /** Only observations get a new identity after a relevant state change, with a total cap. */
    static String observationIdentity(ToolAction action, List<ToolRuntimeStep> steps) {
        if (!"pc".equalsIgnoreCase(action.tool()) || !"SPOTIFY".equalsIgnoreCase(action.operation())) return "";
        String sub = text(action.arguments(), "action").toLowerCase(Locale.ROOT);
        if (!Set.of("status", "devices", "current").contains(sub)) return "";
        long epoch = steps.stream().filter(s -> executed(s) && (desktopOpen(s) && s.result().success()
                || spotify(s, "play"))).count();
        // Before an actual OPEN/play, an unchanged read remains an exact duplicate.
        return epoch == 0 ? "" : "::observation=" + epoch + ":" + Math.min(attempts(steps, sub), MAX_OBSERVATIONS);
    }

    static CompletionAssessment assess(ToolCallingRequest request, List<ToolRuntimeStep> steps) {
        if (!workflowRead(steps)) return missing("Read the complete Spotify workflow first.");
        if (!confirmed(request, steps)) return missing("Playback is not confirmed. Complete status -> devices -> optional desktop OPEN -> devices "
                + "-> " + (fromLiked(request) ? "liked" : "search/library") + " -> play on the local device. After accepted play, read current (bounded) unless observedPlayback "
                + "already proves isPlaying=true on the selected device with the selected URI. Do not repeat the mutation or use Web Player.");
        return CompletionAssessment.ok();
    }

    static String answer(ToolCallingRequest request, List<ToolRuntimeStep> steps) {
        if (confirmed(request, steps)) {
            ToolRuntimeStep play = played(steps);
            Map<?, ?> item = sourceItems(request, steps).get(text(play.arguments(), "target"));
            Map<?, ?> state = observedState(steps, play);
            Object observedItem = state.get("item");
            String title = observedItem instanceof Map<?, ?> track && track.get("name") instanceof String name && !name.isBlank()
                    ? name : String.valueOf(item.get("name"));
            Object artists = observedItem instanceof Map<?, ?> track ? track.get("artists") : null;
            String performer = artists instanceof List<?> names && !names.isEmpty()
                    ? " — " + String.join(", ", names.stream().map(String::valueOf).toList()) : "";
            return "Potwierdzono odtwarzanie Spotify: " + title + performer + ". Urządzenie lokalnego PC: "
                    + localDevice(steps).get("name") + ".";
        }
        String detail = !workflowRead(steps) ? "Nie odczytano kompletnej procedury Spotify."
                : !connected(steps) ? "Nie potwierdzono połączenia konta Spotify."
                : localDevice(steps) == null ? "Nie ustalono dostępnego urządzenia lokalnego PC."
                : played(steps) == null ? "Nie wykonano odtwarzania z potwierdzonym URI i urządzeniem."
                : "Komenda odtwarzania nie została potwierdzona przez obserwowany stan Spotify.";
        return "Zadanie nieukończone — nie potwierdzono odtwarzania. " + detail;
    }

    static boolean confirmed(ToolCallingRequest request, List<ToolRuntimeStep> steps) {
        ToolRuntimeStep play = played(steps);
        Map<?, ?> local = localDevice(steps);
        if (play == null || !play.result().success() || !Boolean.TRUE.equals(play.result().data().get("accepted"))
                || local == null || !Objects.equals(local.get("id"), play.arguments().get("deviceId"))
                || !sourceItems(request, steps).containsKey(text(play.arguments(), "target"))) return false;
        Map<?, ?> state = observedState(steps, play);
        if (!Boolean.TRUE.equals(state.get("isPlaying"))
                || !Objects.equals(play.arguments().get("deviceId"), state.get("deviceId"))) return false;
        Object rawItem = state.get("item");
        String target = text(play.arguments(), "target");
        return target.startsWith("spotify:track:") || target.startsWith("spotify:episode:")
                ? rawItem instanceof Map<?, ?> item && Objects.equals(target, item.get("uri"))
                : Objects.equals(target, state.get("contextUri"));
    }

    private static Map<?, ?> observedState(List<ToolRuntimeStep> steps, ToolRuntimeStep play) {
        Object observed = play.result().data().get("observedPlayback");
        // A later read is authoritative, including a failure, pause, or changed track/device.
        for (int i = steps.indexOf(play) + 1; i < steps.size(); i++) {
            ToolRuntimeStep step = steps.get(i);
            if (executed(step) && spotify(step, "current"))
                observed = step.result().success() ? step.result().data().get("playback") : null;
        }
        return observed instanceof Map<?, ?> state ? state : Map.of();
    }

    private static boolean workflowRead(List<ToolRuntimeStep> steps) {
        Map<String, Set<Integer>> versions = new HashMap<>();
        for (ToolRuntimeStep step : steps) {
            if (!executed(step) || !step.result().success() || !"knowledge".equalsIgnoreCase(step.tool())
                    || !Set.of("READ_WORKFLOW", "READ_DOCUMENT").contains(step.operation().toUpperCase(Locale.ROOT))
                    || !WORKFLOW.equalsIgnoreCase(text(step.arguments(), "path").replace('\\', '/'))) continue;
            Map<String, Object> data = step.result().data();
            if (!(data.get("content") instanceof String content) || content.isBlank()) continue;
            int parts = data.get("parts") instanceof Number n ? n.intValue() : 1;
            int part = data.get("part") instanceof Number n ? n.intValue() : 1;
            if (parts < 1 || parts > 100 || part < 1 || part > parts) continue;
            if (parts == 1 && !Boolean.FALSE.equals(data.get("complete"))) return true;
            String version = text(data, "version");
            if (version.isBlank()) continue;
            Set<Integer> seen = versions.computeIfAbsent(version + ":" + parts, ignored -> new HashSet<>());
            seen.add(part);
            if (seen.size() == parts) return true;
        }
        return false;
    }

    private static Map<?, ?> localDevice(List<ToolRuntimeStep> steps) {
        ToolRuntimeStep read = latest(steps, "devices");
        if (read == null || !read.result().success() || !(read.result().data().get("devices") instanceof List<?> list)) return null;
        // A launch invalidates the old device list until it has actually been read again.
        if (steps.subList(steps.indexOf(read) + 1, steps.size()).stream().anyMatch(s -> executed(s) && desktopOpen(s) && s.result().success())) return null;
        List<Map<?, ?>> matches = new ArrayList<>();
        for (Object raw : list) if (raw instanceof Map<?, ?> d && Boolean.TRUE.equals(d.get("local"))
                && "Computer".equalsIgnoreCase(String.valueOf(d.get("type"))) && !Boolean.TRUE.equals(d.get("restricted"))
                && d.get("id") instanceof String id && !id.isBlank()
                && d.get("name") instanceof String name && !name.toLowerCase(Locale.ROOT).contains("web player")) matches.add(d);
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    private static Map<String, Map<?, ?>> sourceItems(ToolCallingRequest request, List<ToolRuntimeStep> steps) {
        Map<String, Map<?, ?>> items = new LinkedHashMap<>();
        for (ToolRuntimeStep step : steps) if (executed(step) && (fromLiked(request) ? spotify(step, "liked")
                : spotify(step, "search") || spotify(step, "library")) && step.result().success())
            collect(step.result().data(), items);
        return items;
    }

    private static void collect(Object value, Map<String, Map<?, ?>> items) {
        if (value instanceof Map<?, ?> map) {
            if (map.get("uri") instanceof String uri && uri.matches("spotify:(track|episode|album|artist|playlist):[A-Za-z0-9]{22}")
                    && map.get("name") instanceof String name && !name.isBlank()) items.put(uri, map);
            map.values().forEach(child -> collect(child, items));
        } else if (value instanceof List<?> list) list.forEach(child -> collect(child, items));
    }

    private static boolean fromLiked(ToolCallingRequest request) {
        String original = normalize(request.userMessage());
        return original.contains("polubion") || original.contains("liked") || original.contains("ulubion");
    }

    private static boolean connected(List<ToolRuntimeStep> steps) {
        ToolRuntimeStep step = latest(steps, "status");
        return step != null && step.result().success() && Boolean.TRUE.equals(step.result().data().get("connected"));
    }
    private static ToolRuntimeStep played(List<ToolRuntimeStep> steps) { return latest(steps, "play"); }
    private static ToolRuntimeStep latest(List<ToolRuntimeStep> steps, String action) {
        for (int i = steps.size() - 1; i >= 0; i--) if (executed(steps.get(i)) && spotify(steps.get(i), action)) return steps.get(i);
        return null;
    }
    private static int attempts(List<ToolRuntimeStep> steps, String action) {
        return (int) steps.stream().filter(s -> executed(s) && spotify(s, action)).count();
    }
    private static boolean executed(ToolRuntimeStep step) { return "TOOL_CALL".equals(step.action()) && step.result() != null; }
    private static boolean spotify(ToolRuntimeStep s, String a) { return "pc".equalsIgnoreCase(s.tool()) && "SPOTIFY".equalsIgnoreCase(s.operation()) && a.equalsIgnoreCase(text(s.arguments(), "action")); }
    private static boolean desktopOpen(ToolRuntimeStep s) { return "pc".equalsIgnoreCase(s.tool()) && "OPEN".equalsIgnoreCase(s.operation()) && "spotify".equalsIgnoreCase(text(s.arguments(), "target")); }
    private static CompletionAssessment missing(String guidance) { return new CompletionAssessment(false, BLOCKED, guidance); }
    private static String text(Map<String, Object> data, String key) { return Objects.toString(data.get(key), "").strip(); }
    private static String normalize(String text) { return Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "").replace('ł', 'l'); }
}
