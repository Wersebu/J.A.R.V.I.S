package com.jarvis.tools.runtime;

import java.util.*;

/** Detects repeated evidence across successful read/search calls, even with varying arguments.
 * Does not pretend to judge semantic task completion. Mutations reset the evidence epoch.
 */
final class ReadProgressGuard {
    private final Set<String> seen = new HashSet<>();
    private int stagnant;
    private boolean warned;

    void observe(String tool, String operation, boolean success, boolean changed, Map<String, Object> data) {
        if (changed && success) { seen.clear(); stagnant = 0; warned = false; return; }
        if (!Set.of("pc", "coding", "output").contains(tool.toLowerCase(Locale.ROOT))) return;
        if (!Set.of("READ", "GREP", "FIND", "LIST", "INSPECT", "FILE_READ", "FILE_LIST", "FILE_SEARCH")
                .contains(operation.toUpperCase(Locale.ROOT))) return;
        Set<String> evidence = new HashSet<>();
        collect(data, "", evidence);
        int fresh = 0;
        for (String item : evidence) if (seen.add(item)) fresh++;
        // Overlapping pages may repeat hundreds of lines but expose a genuinely new section.
        if (success && fresh > 0) stagnant = 0;
        else stagnant++;
    }

    boolean shouldWarn() { if (stagnant >= 4 && !warned) { warned = true; return true; } return false; }
    boolean shouldStop() { return stagnant >= 8; }

    private void collect(Object value, String key, Set<String> evidence) {
        if (value instanceof Map<?, ?> map) {
            map.forEach((k, v) -> {
                String field = String.valueOf(k);
                if (!Set.of("requestId", "outputId", "timestamp", "durationMs", "elapsedMs", "startLine", "endLine",
                        "limit", "offset", "pattern", "query", "line", "lineNumber", "totalLines", "truncated",
                        "workingDirectory", "operation", "success", "message").contains(field)) collect(v, field, evidence);
            });
        } else if (value instanceof Collection<?> list) {
            list.forEach(v -> collect(v, key, evidence));
        } else if (value instanceof String text) {
            // Numbered read/grep output: the same source line remains the same evidence.
            text.lines().map(line -> line.replaceFirst("^\\s*\\d+\\s*[:|]\\s*", "").strip())
                    .filter(line -> line.length() >= 8).forEach(line -> evidence.add(line));
        }
    }
}
