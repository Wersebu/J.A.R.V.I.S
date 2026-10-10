package com.jarvis.tools.runtime;

import com.jarvis.tools.workflow.CompletionAssessment;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Evidence contract for the compound Spotify connection-and-devices read request. */
final class SpotifyReadEvidence {
    private static final Pattern STATUS = Pattern.compile("(polacz|connection|connected|status)");
    private static final Pattern DEVICES = Pattern.compile("(urzadzen|devices?)");
    static final String MISSING = "SPOTIFY_READ_EVIDENCE_REQUIRED";

    private SpotifyReadEvidence() { }

    static boolean applies(ToolCallingRequest request) {
        // The original request is authoritative; a model-generated subgoal cannot drop a criterion.
        String text = Normalizer.normalize(request.userMessage(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).replace('ł', 'l');
        return text.contains("spotify") && STATUS.matcher(text).find() && DEVICES.matcher(text).find();
    }

    static CompletionAssessment assess(List<ToolRuntimeStep> steps) {
        List<String> missing = new ArrayList<>();
        if (status(steps) == null) missing.add("pc__spotify(action=status), with boolean connected");
        if (devices(steps) == null) missing.add("pc__spotify(action=devices), with a devices list");
        if (missing.isEmpty()) return CompletionAssessment.ok();
        return new CompletionAssessment(false, MISSING,
                "Missing evidence for separate parts of the original goal: " + String.join("; ", missing)
                        + ". A status result does not contain device evidence. Continue with the missing safe read. "
                        + "Never infer device names from status, config, hints or prior model text. "
                        + "Do not connect or play music. If the read fails, report the unfinished part explicitly.");
    }

    static String answer(List<ToolRuntimeStep> steps) {
        Boolean connected = status(steps);
        List<?> devices = devices(steps);
        List<String> lines = new ArrayList<>();
        if (connected == null) lines.add("Nie potwierdzono stanu połączenia Spotify — odczyt statusu nie został ukończony.");
        else lines.add(connected ? "Spotify jest połączone." : "Spotify nie jest połączone.");
        if (devices == null) lines.add("Nie ukończono odczytu dostępnych urządzeń Spotify. Nie mam potwierdzonej listy urządzeń.");
        else if (devices.isEmpty()) lines.add("Dostępne urządzenia: 0 (Spotify zwróciło pustą listę).");
        else {
            lines.add("Dostępne urządzenia (" + devices.size() + "):");
            for (Object item : devices) {
                Map<?, ?> device = (Map<?, ?>) item;
                String name = (String) device.get("name");
                Object type = device.get("type");
                lines.add("- " + name + (type instanceof String value && !value.isBlank() ? " (" + value + ")" : ""));
            }
        }
        return String.join("\n", lines);
    }

    private static Boolean status(List<ToolRuntimeStep> steps) {
        Map<String, Object> data = latest(steps, "status");
        return data != null && data.get("connected") instanceof Boolean value ? value : null;
    }

    private static List<?> devices(List<ToolRuntimeStep> steps) {
        Map<String, Object> data = latest(steps, "devices");
        if (data == null || !(data.get("devices") instanceof List<?> list)) return null;
        // An empty list is real evidence. Malformed results are not silently treated as empty.
        return list.stream().allMatch(item -> item instanceof Map<?, ?> device
                && device.get("name") instanceof String name && !name.isBlank()) ? list : null;
    }

    private static Map<String, Object> latest(List<ToolRuntimeStep> steps, String action) {
        for (int i = steps.size() - 1; i >= 0; i--) {
            ToolRuntimeStep step = steps.get(i);
            if ("pc".equalsIgnoreCase(step.tool()) && "SPOTIFY".equalsIgnoreCase(step.operation())
                    && action.equalsIgnoreCase(String.valueOf(step.arguments().get("action")).strip())
                    && step.result() != null) {
                return step.result().success() ? step.result().data() : null;
            }
        }
        return null;
    }
}
