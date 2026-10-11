package com.jarvis.tools.runtime;

import com.jarvis.tools.workflow.CompletionAssessment;
import com.jarvis.knowledge.vault.read.WorkflowCandidate;
import java.text.Normalizer;
import java.util.*;

/** Per-request desktop playback procedure. Decisions depend on executed calls, never model prose. */
final class SpotifyPlaybackPolicy {
    static final String BLOCKED = "SPOTIFY_PROCEDURE_REQUIRED";
    static final String WORKFLOW = "workflows/Spotify.md";
    private static final java.util.regex.Pattern PLAY =
            java.util.regex.Pattern.compile("\\b(play|listen)\\b|\\b(odpal|pusc|puszcz|wlacz|zagraj|odtworz|wrzuc|przenies|przerzuc|zapusc)\\w*");
    private static final java.util.regex.Pattern NEGATED = java.util.regex.Pattern.compile(
            "nie (uruchamiaj|odtwarzaj|wlaczaj|puszczaj)[^.!?]*|(do not|don't|without) (play|start)[^.!?]*");
    private static final int MAX_OBSERVATIONS = 4; // initial read plus three bounded readiness observations

    static boolean applies(ToolCallingRequest request) {
        if (SpotifyReadEvidence.operationsForbidden(request)) return false;
        String text = normalize(request.userMessage());
        String domain = text + " " + normalize(Objects.toString(request.goal(), "")) + " " + normalize(request.conversationContext());
        if (!domain.contains("spotify") || text.contains("web player")) return false;
        return PLAY.matcher(NEGATED.matcher(text).replaceAll("")).find();
    }

    static String guard(ToolCallingRequest request, ToolAction action, List<ToolRuntimeStep> steps) {
        String tool = action.tool().toLowerCase(Locale.ROOT);
        String op = action.operation().toUpperCase(Locale.ROOT);
        if (tool.equals("knowledge")) {
            if (op.equals("FIND_WORKFLOW")) return "";
            if (Set.of("READ_WORKFLOW", "READ_DOCUMENT").contains(op)) {
                String path = text(action.arguments(), "path");
                if (workflowPaths(steps).contains(path)) return "";
                // Once the procedure is read, other vault notes (e.g. music preferences) are ordinary reads.
                if (op.equals("READ_DOCUMENT") && workflowRead(steps)) return "";
                return "Use knowledge__find_workflow(query=Spotify), then READ_WORKFLOW with the exact returned candidate path. "
                        + "Do not prefix directories or invent filenames. Routed path: " + WORKFLOW;
            }
            if (!workflowRead(steps)) return "Find the procedure with knowledge__find_workflow(query=Spotify); folder listings and search fragments do not replace it.";
            if (Set.of("SEARCH_CONTENT", "SEARCH_DOCUMENT", "LIST_TREE", "LIST_FOLDER").contains(op)) return "";
        }
        if (tool.equals("plan") || tool.equals("system") && op.equals("NOTIFY_USER")) return "";
        // The composite tool reads the procedure itself and applies this policy to each of its sub-calls.
        if (tool.equals("pc") && (op.equals(SpotifyAutopilot.OPERATION) || op.equals(SpotifyAutopilot.CONTROL))) return "";
        if (!workflowRead(steps)) return workflowGuidance(steps);
        if (!tool.equals("pc")) return "Use desktop pc__spotify; do not switch to browser/Web Player or other execution tools.";
        if (op.equals("OPEN")) {
            if (!"spotify".equalsIgnoreCase(text(action.arguments(), "target")))
                return "Only pc__open(target=spotify) may launch the desktop app. Do not open track URLs or Web Player.";
            if (!connected(steps) || latest(steps, "devices") == null)
                return "First read status, then devices. Opening the app does not establish Spotify authorization.";
            if (localDevice(steps) != null) return "The local PC is already available. Use search and play; opening again is not needed.";
            return "";
        }
        if (op.equals("READ") || op.equals("LIST") || op.equals("FIND"))
            return "PC files are not part of playback. Notes such as preferences live in the knowledge vault (knowledge__read_document); "
                    + "if they do not exist, do not search further: use pc__spotify(action=liked).";
        if (!op.equals("SPOTIFY")) return "Use pc__spotify, not MEDIA, shell, browser, or URL-based playback.";
        String sub = text(action.arguments(), "action").toLowerCase(Locale.ROOT);
        if (!Set.of("status", "devices", "search", "liked", "library", "play", "current").contains(sub))
            return "This playback request does not authorize connect/disconnect or unrelated mutations.";
        if (Set.of("status", "devices", "current").contains(sub) && attempts(steps, sub) >= MAX_OBSERVATIONS)
            return "Spotify observation budget exhausted. Report playback as unconfirmed; do not loop.";
        if (sub.equals("status")) return "";
        if (!connected(steps)) return "Read status first. If connected=false, report that login is required; do not automatically connect.";
        // Reading a source does not touch any device; only play needs the identified local PC.
        if (Set.of("devices", "liked", "library", "search").contains(sub)) return "";
        if (sub.equals("current")) return played(steps) == null ? "Issue a validated play before verifying it with current." : "";
        Map<?, ?> device = targetDevice(request, steps, action.arguments().get("deviceId"));
        if (device == null && localDevice(steps) == null && latestDevices(steps).stream().noneMatch(d -> remoteRequested(request, d)))
            return "Read devices and identify exactly one local=true Computer. If absent, open the desktop app once, "
                + "then read devices again. Do not select another computer, phone or Web Player unless the user asked for that device. "
                + "A Windows client with local-device metadata is required.";
        if (played(steps) != null) return "A play was already attempted. Do not duplicate a possibly accepted mutation; verify with current or report partial completion.";
        String uri = text(action.arguments(), "target");
        if (!sourceItems(request, steps).containsKey(uri)) return "Play target must be an exact URI from " + (fromLiked(request) ? "liked" : "search/library/liked") + " in this request. "
                + "Read that source and select a real result; never use a URI from model memory or an earlier assistant message. "
                + "Invalid URI is not an authentication failure: do not connect.";
        if (device == null)
            return "Specify deviceId of the local=true Computer from the latest devices result, or of the device the user explicitly asked for.";
        return "";
    }

    /**
     * Fills in what Core already knows, so weak models do not fail on bookkeeping: play always targets the
     * identified local PC, and a returned open.spotify.com link or exact result name maps to its URI.
     * Never invents a target: anything not traceable to a source result in this request stays as given.
     */
    static ToolAction prepare(ToolCallingRequest request, ToolAction action, List<ToolRuntimeStep> steps) {
        if (!"pc".equalsIgnoreCase(action.tool()) || !"SPOTIFY".equalsIgnoreCase(action.operation())
                || !"play".equalsIgnoreCase(text(action.arguments(), "action"))) return action;
        Map<String, Object> args = new LinkedHashMap<>(action.arguments());
        // Keep a device the user asked for (by id or name); anything else means this PC.
        Map<?, ?> device = targetDevice(request, steps, args.get("deviceId"));
        if (device == null && !text(args, "deviceName").isBlank()) device = requestedDevice(request, steps, text(args, "deviceName"));
        // The device the user named ("na telewizorze") beats a model that left it out or fell back to this PC.
        Map<?, ?> asked = userRequestedDevice(request, steps);
        if (asked != null && (device == null || device == localDevice(steps))) device = asked;
        if (device == null) device = localDevice(steps);
        if (device != null) {
            args.put("deviceId", device.get("id"));
            args.remove("deviceName");
        }
        Map<String, Map<?, ?>> items = sourceItems(request, steps);
        String target = text(args, "target");
        if (!target.isBlank() && !items.containsKey(target)) {
            java.util.regex.Matcher link = java.util.regex.Pattern
                    .compile("open\\.spotify\\.com/(?:intl-[a-z]+/)?(track|episode|album|artist|playlist)/([A-Za-z0-9]{22})").matcher(target);
            String resolved = link.find() ? "spotify:" + link.group(1) + ":" + link.group(2) : "";
            if (resolved.isEmpty()) {
                List<String> byName = items.entrySet().stream()
                        .filter(e -> normalize(String.valueOf(e.getValue().get("name"))).equals(normalize(target)))
                        .map(Map.Entry::getKey).toList();
                if (byName.size() == 1) resolved = byName.getFirst();
            }
            if (resolved.isEmpty()) resolved = closestUri(target, items.keySet());
            if (items.containsKey(resolved)) args.put("target", resolved);
        }
        // A track taken from liked songs plays inside Liked Songs, so the queue continues instead of ending after it.
        if (likedUris(steps).contains(text(args, "target"))) args.put("context", "liked");
        return args.equals(action.arguments()) ? action
                : new ToolAction(action.action(), action.tool(), action.operation(), args, action.reason(), action.answer());
    }

    /**
     * Small models often miscopy base62 IDs (U/u, o/0, a dropped character). A target within three edits of
     * exactly one returned URI of the same type, and clearly closer than any other, is that result.
     */
    private static String closestUri(String target, Set<String> uris) {
        java.util.regex.Matcher typed = java.util.regex.Pattern.compile("spotify:(track|episode|album|artist|playlist):([A-Za-z0-9]{18,26})").matcher(target.strip());
        if (!typed.matches()) return "";
        String best = "";
        int bestDistance = Integer.MAX_VALUE, second = Integer.MAX_VALUE;
        for (String uri : uris) {
            if (!uri.startsWith("spotify:" + typed.group(1) + ":")) continue;
            int distance = editDistance(typed.group(2), uri.substring(uri.lastIndexOf(':') + 1));
            if (distance < bestDistance) { second = bestDistance; bestDistance = distance; best = uri; }
            else if (distance < second) second = distance;
        }
        return bestDistance <= 3 && second - bestDistance >= 4 ? best : "";
    }

    private static int editDistance(String a, String b) {
        int[] previous = new int[b.length() + 1], current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) previous[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) current[j] = Math.min(Math.min(current[j - 1], previous[j]) + 1,
                    previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            int[] swap = previous; previous = current; current = swap;
        }
        return previous[b.length()];
    }

    /** The single next call the procedure needs, stated concretely for models that stop or wander mid-way. */
    static String nextStep(ToolCallingRequest request, List<ToolRuntimeStep> steps) {
        if (!workflowRead(steps)) return workflowGuidance(steps);
        if (latest(steps, "status") == null) return "Next call: pc__spotify(action=status).";
        if (!connected(steps)) return "Spotify is not connected. Report that the user must connect Spotify; do not connect automatically.";
        Map<?, ?> device = localDevice(steps);
        if (device == null) {
            ToolRuntimeStep devices = latest(steps, "devices");
            boolean openedAfter = steps.stream().anyMatch(s -> executed(s) && desktopOpen(s) && s.result().success()
                    && (devices == null || steps.indexOf(s) > steps.indexOf(devices)));
            if (devices == null || openedAfter) return "Next call: pc__spotify(action=devices).";
            if (steps.stream().noneMatch(s -> executed(s) && desktopOpen(s)))
                return "No local=true Computer in devices. Next call: pc__open(target=spotify), then pc__spotify(action=devices).";
            return "The desktop app is still not visible as local=true Computer. Read devices once more if the budget allows; otherwise report that "
                    + "this PC is not visible in Spotify Connect (deviceName in Windows config/spotify.json must match the Spotify device name).";
        }
        if (played(steps) != null) return "Play was already sent. Next call: pc__spotify(action=current) to verify; do not repeat play.";
        Map<String, Map<?, ?>> items = sourceItems(request, steps);
        if (items.isEmpty()) return fromLiked(request) ? "Next call: pc__spotify(action=liked)."
                : "Next call: pc__spotify(action=search, query=<what the user asked for>, type=track). If the user named nothing specific "
                + "(e.g. 'play something'), do not ask and do not stop: call pc__spotify(action=liked) and play one returned track.";
        Map.Entry<String, Map<?, ?>> first = items.entrySet().iterator().next();
        return "Next call: pc__spotify(action=play, target=" + first.getKey() + ") - or another URI returned in this request. "
                + "target may also be the exact result name (\"" + first.getValue().get("name") + "\"); Core resolves it and fills the local deviceId. "
                + "Do not stop before play.";
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
        // "Next one", "pause", "louder": a control of the live session is the whole request, not a new playback.
        if (control(steps) != null) return CompletionAssessment.ok();
        if (!workflowRead(steps)) return missing(workflowGuidance(steps));
        if (!confirmed(request, steps)) return missing("Playback is not confirmed. " + nextStep(request, steps)
                + " Procedure: status -> devices -> optional desktop OPEN -> devices -> " + (fromLiked(request) ? "liked" : "search/library/liked")
                + " -> play on the local device -> verify observedPlayback or current. Do not repeat the mutation or use Web Player.");
        return CompletionAssessment.ok();
    }

    static String answer(ToolCallingRequest request, List<ToolRuntimeStep> steps) {
        if (confirmed(request, steps)) {
            Map<String, Object> facts = playbackFacts(request, steps);
            String performer = facts.containsKey("artists") ? " — " + facts.get("artists") : "";
            return "Potwierdzono odtwarzanie Spotify: " + facts.get("title") + performer + ". "
                    + (Boolean.TRUE.equals(facts.get("localPc")) ? "Urządzenie lokalnego PC: " : "Urządzenie: ") + facts.get("device") + ".";
        }
        String detail = !workflowRead(steps) ? "Nie odczytano kompletnej procedury Spotify."
                : !connected(steps) ? "Nie potwierdzono połączenia konta Spotify."
                : played(steps) == null && localDevice(steps) == null ? "Nie ustalono dostępnego urządzenia do odtwarzania."
                : played(steps) == null ? "Nie wykonano odtwarzania z potwierdzonym URI i urządzeniem."
                : "Komenda odtwarzania nie została potwierdzona przez obserwowany stan Spotify.";
        return "Zadanie nieukończone — nie potwierdzono odtwarzania. " + detail;
    }

    /**
     * The model's own reply when playback is confirmed and the reply names the track actually playing, with no
     * links, URIs or raw structures; otherwise the evidence-based {@link #answer}. Lets Jarvis speak in its own
     * voice without ever publishing a claim the observed Spotify state does not support.
     */
    static String groundedAnswer(ToolCallingRequest request, List<ToolRuntimeStep> steps, String modelAnswer) {
        ToolRuntimeStep control = control(steps);
        String evidence = control != null ? control.result().message() : answer(request, steps);
        if (modelAnswer == null || modelAnswer.isBlank() || modelAnswer.length() > 800) return evidence;
        String reply = normalize(modelAnswer);
        boolean clean = !reply.contains("http") && !reply.contains("spotify:") && !reply.contains("{") && !reply.contains("open.spotify");
        if (!clean) return evidence;
        if (control != null) {
            if (control.result().success()) {
                Object now = control.result().data().get("title");
                String title = normalize(Objects.toString(now, "")).split(" - | \\(| \\[")[0].strip();
                return now == null || title.length() >= 2 && reply.contains(title) ? modelAnswer.strip() : evidence;
            }
            return ADMITS_FAILURE.matcher(reply).find() && !CLAIMS_PLAYBACK.matcher(reply).find() ? modelAnswer.strip() : evidence;
        }
        if (confirmed(request, steps)) {
            String title = normalize(String.valueOf(playbackFacts(request, steps).get("title"))).split(" - | \\(| \\[")[0].strip();
            ToolRuntimeStep play = played(steps);
            Map<?, ?> device = targetDevice(request, steps, play.arguments().get("deviceId"));
            return title.length() >= 2 && reply.contains(title) && !mentionsOtherDevice(modelAnswer, device, steps) ? modelAnswer.strip() : evidence;
        }
        // Nothing confirmed: the model may explain why in its own words (e.g. the TV is not visible to Spotify),
        // but only an explicit admission passes - never a reply that claims or implies playback.
        return ADMITS_FAILURE.matcher(reply).find() && !CLAIMS_PLAYBACK.matcher(reply).find() ? modelAnswer.strip() : evidence;
    }

    private static final java.util.regex.Pattern ADMITS_FAILURE = java.util.regex.Pattern.compile(
            "\\bnie (widze|moge|udalo|znalazl|znajduje|ma |jest dostep|potwierdz|da sie)|niedostep|\\bbrak\\b|\\bnie wykryl"
                    + "|can't|cannot|unable|not available|not visible|not found|don't see|couldn't|could not");
    private static final java.util.regex.Pattern CLAIMS_PLAYBACK = java.util.regex.Pattern.compile(
            "\\b(odtwarzam|wlaczylem|wlaczylam|wlaczam|puscilem|puscilam|puszczam|uruchomilem|odpalilem|odpalam|leci|gra juz|juz gra"
                    + "|gotowe|zrobione|now playing|is playing|started playing|done)\\b");

    static boolean confirmed(ToolCallingRequest request, List<ToolRuntimeStep> steps) {
        ToolRuntimeStep play = played(steps);
        if (play == null || !play.result().success() || !Boolean.TRUE.equals(play.result().data().get("accepted"))
                || targetDevice(request, steps, play.arguments().get("deviceId")) == null
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

    /** Facts of the confirmed (or attempted) playback, for the composite tool's result and the final answer. */
    static Map<String, Object> playbackFacts(ToolCallingRequest request, List<ToolRuntimeStep> steps) {
        Map<String, Object> facts = new LinkedHashMap<>();
        ToolRuntimeStep play = played(steps);
        if (play == null) return facts;
        String target = text(play.arguments(), "target");
        Map<?, ?> item = sourceItems(request, steps).getOrDefault(target, Map.of());
        Object observedItem = observedState(steps, play).get("item");
        String title = observedItem instanceof Map<?, ?> track && track.get("name") instanceof String name && !name.isBlank()
                ? name : String.valueOf(item.get("name"));
        Object artists = observedItem instanceof Map<?, ?> track && track.get("artists") != null ? track.get("artists") : item.get("artists");
        facts.put("title", title);
        if (artists instanceof List<?> names && !names.isEmpty())
            facts.put("artists", String.join(", ", names.stream().map(String::valueOf).toList()));
        facts.put("uri", target);
        Map<?, ?> device = targetDevice(request, steps, play.arguments().get("deviceId"));
        if (device != null) {
            facts.put("device", device.get("name"));
            facts.put("localPc", device == localDevice(steps));
        }
        if ("liked".equals(play.arguments().get("context"))) facts.put("context", "Liked Songs (queue continues)");
        return facts;
    }

    /** The play target device: the identified local PC, or a device the user explicitly asked for. */
    static Map<?, ?> targetDevice(ToolCallingRequest request, List<ToolRuntimeStep> steps, Object deviceId) {
        if (deviceId == null) return null;
        Map<?, ?> local = localDevice(steps);
        if (local != null && Objects.equals(local.get("id"), deviceId)) return local;
        return latestDevices(steps).stream().filter(d -> Objects.equals(d.get("id"), deviceId) && remoteRequested(request, d))
                .findFirst().orElse(null);
    }

    /** A non-local device named by the model, accepted only when the user's own message asks for it. */
    static Map<?, ?> requestedDevice(ToolCallingRequest request, List<ToolRuntimeStep> steps, String wanted) {
        String key = compact(wanted);
        List<Map<?, ?>> matches = latestDevices(steps).stream().filter(d -> remoteRequested(request, d))
                .filter(d -> key.isEmpty() || wanted.strip().equals(d.get("id")) || compact(String.valueOf(d.get("name"))).contains(key)
                        || key.contains(compact(String.valueOf(d.get("name"))))
                        || kindWords(String.valueOf(d.get("type"))).stream().anyMatch(key::contains))
                .toList();
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    /** The one non-local device the user's own message asks for (by name or kind), if any. */
    static Map<?, ?> userRequestedDevice(ToolCallingRequest request, List<ToolRuntimeStep> steps) {
        Map<?, ?> local = localDevice(steps);
        // "Z telewizora na komputer" names both: then the model's own choice stands.
        List<String> words = List.of(normalize(request.userMessage()).split("[^a-z0-9]+"));
        if (words.stream().anyMatch(word -> word.startsWith("komputer") || word.equals("pc") || word.startsWith("laptop"))
                || local != null && compact(request.userMessage()).contains(compact(String.valueOf(local.get("name"))))) return null;
        List<Map<?, ?>> asked = latestDevices(steps).stream()
                .filter(d -> local == null || !Objects.equals(d.get("id"), local.get("id")))
                .filter(d -> remoteRequested(request, d)).toList();
        return asked.size() == 1 ? asked.getFirst() : null;
    }

    /** "Odpal to na TV", "put this on the speaker": the user means what is playing now, not something new. */
    static boolean refersToCurrent(ToolCallingRequest request) {
        return java.util.regex.Pattern.compile("\\b(to|tego|ta|te|this|it|przenies\\w*|przerzuc\\w*|move|transfer\\w*)\\b")
                .matcher(normalize(request.userMessage())).find();
    }

    /** A reply naming a device other than the one actually playing (e.g. "on your TV" while the PC plays) is false. */
    private static boolean mentionsOtherDevice(String reply, Map<?, ?> actual, List<ToolRuntimeStep> steps) {
        if (actual == null) return true;
        String text = compact(reply);
        List<String> actualKinds = kindWords(String.valueOf(actual.get("type")));
        for (Map<?, ?> device : latestDevices(steps)) {
            if (Objects.equals(device.get("id"), actual.get("id"))) continue;
            String name = compact(String.valueOf(device.get("name")));
            if (name.length() >= 3 && text.contains(name)) return true;
            if (kindWords(String.valueOf(device.get("type"))).stream().filter(word -> !actualKinds.contains(word)).anyMatch(text::contains))
                return true;
        }
        return false;
    }

    static boolean isLocalDeviceWord(String device) {
        return Set.of("", "local", "localpc", "pc", "thispc", "thiscomputer", "computer", "komputer", "tenkomputer", "tenpc", "desktop")
                .contains(compact(device));
    }

    static List<String> visibleDeviceNames(List<ToolRuntimeStep> steps) {
        return latestDevices(steps).stream().map(d -> d.get("name") + " (" + d.get("type") + ")").toList();
    }

    private static List<Map<?, ?>> latestDevices(List<ToolRuntimeStep> steps) {
        ToolRuntimeStep read = latest(steps, "devices");
        if (read == null || !read.result().success() || !(read.result().data().get("devices") instanceof List<?> list)) return List.of();
        List<Map<?, ?>> devices = new ArrayList<>();
        for (Object raw : list) if (raw instanceof Map<?, ?> d && d.get("id") instanceof String id && !id.isBlank()) devices.add(d);
        return devices;
    }

    /**
     * Playing anywhere but this PC needs the user's own words: the device name or its kind (TV, speaker...).
     * The model's arguments alone never authorize another device, so it cannot drift to a phone or another PC.
     */
    private static boolean remoteRequested(ToolCallingRequest request, Map<?, ?> device) {
        if (Boolean.TRUE.equals(device.get("restricted")) || String.valueOf(device.get("name")).toLowerCase(Locale.ROOT).contains("web player"))
            return false;
        String message = compact(request.userMessage());
        String name = compact(String.valueOf(device.get("name")));
        if (name.length() >= 3 && message.contains(name)) return true;
        String type = String.valueOf(device.get("type"));
        return !"Computer".equalsIgnoreCase(type) && kindWords(type).stream().anyMatch(message::contains);
    }

    private static List<String> kindWords(String type) {
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "tv", "castvideo", "stb" -> List.of("telewizor", "tv", "telewizja");
            case "speaker", "avr", "castaudio", "audiodongle" -> List.of("glosnik", "soundbar", "speaker", "kolumn", "wiez");
            case "smartphone" -> List.of("telefon", "phone", "komork");
            case "tablet" -> List.of("tablet");
            case "gameconsole" -> List.of("konsol", "console", "xbox", "playstation");
            case "automobile" -> List.of("samochod", "car");
            default -> List.of();
        };
    }

    private static String compact(String text) {
        return normalize(Objects.toString(text, "")).replaceAll("[^a-z0-9]", "");
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

    private static String workflowGuidance(List<ToolRuntimeStep> steps) {
        return "Use knowledge__find_workflow(query=Spotify) to find candidates, then knowledge__read_workflow with "
                + "the exact returned path and every part. Never invent paths or merely announce a search. "
                + "Currently grounded vault-relative paths: " + workflowPaths(steps)
                + ". If discovery is unavailable or returns no matching candidate, report the procedure as unavailable.";
    }

    static Set<String> workflowPaths(List<ToolRuntimeStep> steps) {
        Set<String> paths = new LinkedHashSet<>();
        paths.add(WORKFLOW); // vault-relative routing hint, never an OS path
        for (ToolRuntimeStep step : steps) {
            if (!executed(step) || !"knowledge".equalsIgnoreCase(step.tool())) continue;
            if (Set.of("READ_WORKFLOW", "READ_DOCUMENT").contains(step.operation()) && !step.result().success()) {
                paths.remove(text(step.arguments(), "path"));
            }
            if ("FIND_WORKFLOW".equals(step.operation()) && step.result().success()
                    && step.result().data().get("candidates") instanceof List<?> candidates) {
                paths.clear();
                for (Object value : candidates) {
                    String path = "", title = "";
                    if (value instanceof WorkflowCandidate candidate) {
                        path = candidate.path(); title = candidate.title();
                    } else if (value instanceof Map<?, ?> candidate) {
                        path = Objects.toString(candidate.get("path"), "");
                        title = Objects.toString(candidate.get("title"), "");
                    }
                    if (!path.isBlank() && normalize(path + " " + title).contains("spotify")) paths.add(path);
                }
            }
        }
        return paths;
    }

    static boolean workflowRead(List<ToolRuntimeStep> steps) {
        Map<String, Set<Integer>> versions = new HashMap<>();
        for (int index = 0; index < steps.size(); index++) {
            ToolRuntimeStep step = steps.get(index);
            if (!executed(step) || !step.result().success() || !"knowledge".equalsIgnoreCase(step.tool())
                    || !Set.of("READ_WORKFLOW", "READ_DOCUMENT").contains(step.operation().toUpperCase(Locale.ROOT))
                    || !workflowPaths(steps.subList(0, index)).contains(text(step.arguments(), "path"))) continue;
            Map<String, Object> data = step.result().data();
            if (!(data.get("content") instanceof String content) || content.isBlank()) continue;
            int parts = data.get("parts") instanceof Number n ? n.intValue() : 1;
            int part = data.get("part") instanceof Number n ? n.intValue() : 1;
            if (parts < 1 || parts > 100 || part < 1 || part > parts) continue;
            if (parts == 1 && !Boolean.FALSE.equals(data.get("complete"))) return true;
            String version = text(data, "version");
            if (version.isBlank()) continue;
            Set<Integer> seen = versions.computeIfAbsent(text(step.arguments(), "path") + ":" + version + ":" + parts, ignored -> new HashSet<>());
            seen.add(part);
            if (seen.size() == parts) return true;
        }
        return false;
    }

    static Map<?, ?> localDevice(List<ToolRuntimeStep> steps) {
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

    static Map<String, Map<?, ?>> sourceItems(ToolCallingRequest request, List<ToolRuntimeStep> steps) {
        Map<String, Map<?, ?>> items = new LinkedHashMap<>();
        // Liked songs are the user's own data, so they also ground a generic "play something". What was playing
        // before any play in this request grounds moving it to another device.
        boolean playedYet = false;
        for (ToolRuntimeStep step : steps) {
            if (executed(step) && step.result().success() && (spotify(step, "liked")
                    || !fromLiked(request) && (spotify(step, "search") || spotify(step, "library"))
                    || !playedYet && spotify(step, "current")))
                collect(step.result().data(), items);
            playedYet |= executed(step) && spotify(step, "play");
        }
        return items;
    }

    private static Set<String> likedUris(List<ToolRuntimeStep> steps) {
        Map<String, Map<?, ?>> items = new LinkedHashMap<>();
        for (ToolRuntimeStep step : steps) if (executed(step) && spotify(step, "liked") && step.result().success())
            collect(step.result().data(), items);
        return items.keySet();
    }

    static void collect(Object value, Map<String, Map<?, ?>> items) {
        if (value instanceof Map<?, ?> map) {
            if (map.get("uri") instanceof String uri && uri.matches("spotify:(track|episode|album|artist|playlist):[A-Za-z0-9]{22}")
                    && map.get("name") instanceof String name && !name.isBlank()
                    // Spotify marks tracks unavailable in the user's market; playing them silently does nothing.
                    && !Boolean.FALSE.equals(map.get("is_playable")) && !Boolean.TRUE.equals(map.get("is_local"))) items.put(uri, map);
            map.values().forEach(child -> collect(child, items));
        } else if (value instanceof List<?> list) list.forEach(child -> collect(child, items));
    }

    private static boolean fromLiked(ToolCallingRequest request) {
        String original = normalize(request.userMessage());
        return original.contains("polubion") || original.contains("liked") || original.contains("ulubion");
    }

    static boolean connected(List<ToolRuntimeStep> steps) {
        ToolRuntimeStep step = latest(steps, "status");
        return step != null && step.result().success() && Boolean.TRUE.equals(step.result().data().get("connected"));
    }
    /**
     * The latest call that completes the request without new playback: pc__spotify_control (next, pause, now,
     * like...) or pc__spotify_play with mode=queue that queued a track.
     */
    static ToolRuntimeStep control(List<ToolRuntimeStep> steps) {
        for (int i = steps.size() - 1; i >= 0; i--) {
            ToolRuntimeStep step = steps.get(i);
            if (!executed(step) || !"pc".equalsIgnoreCase(step.tool())) continue;
            if (SpotifyAutopilot.CONTROL.equalsIgnoreCase(step.operation())) return step;
            if (SpotifyAutopilot.OPERATION.equalsIgnoreCase(step.operation()) && step.result().data().containsKey("queued")) return step;
        }
        return null;
    }

    static ToolRuntimeStep played(List<ToolRuntimeStep> steps) { return latest(steps, "play"); }
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
    static String normalize(String text) { return Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "").replace('ł', 'l'); }
}
