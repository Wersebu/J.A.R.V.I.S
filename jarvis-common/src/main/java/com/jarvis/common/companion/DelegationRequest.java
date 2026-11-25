package com.jarvis.common.companion;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** Narrow routing signal from the original user message, never from model-generated context. */
public final class DelegationRequest {
    private DelegationRequest() {}
    public static int requestedTasks(String message) {
        if (message == null) return 0;
        String text = Normalizer.normalize(message, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replace('ł', 'l');
        boolean async = Pattern.compile("background|asynchron|w tle|niezalezn|independent").matcher(text).find();
        boolean agent = Pattern.compile("agent|deleg|companion").matcher(text).find();
        boolean action = Pattern.compile("run|start|launch|spawn|create|submit|deleg|uruchom|odpal|stworz|zlec|wlacz").matcher(text).find();
        if (!async || !agent || !action || Pattern.compile("(?:do not|don't|nie)\\s+(?:run|start|create|deleg|urucham|uruchom|tworz)").matcher(text).find()) return 0;
        var count = Pattern.compile("\\b([1-9][0-9]?|one|two|three|four|five|jeden|jednego|dwa|dwoch|trzy|trzech|cztery|czterech|piec)\\s+(?:(?:independent|background|separate|niezaleznych|niezalezne|osobnych|osobne)\\s+){0,3}agent").matcher(text);
        if (!count.find()) return 1;
        return switch (count.group(1)) {
            case "one", "jeden", "jednego" -> 1;
            case "two", "dwa", "dwoch" -> 2;
            case "three", "trzy", "trzech" -> 3;
            case "four", "cztery", "czterech" -> 4;
            case "five", "piec" -> 5;
            default -> Math.min(32, Integer.parseInt(count.group(1)));
        };
    }
}
