package com.jarvis.common.ai;

import java.util.LinkedHashMap;
import java.util.Map;

/** Request-scoped accumulator. Missing provider counts remain unknown, not measured zero. */
public final class RequestTokenUsage {
    private long input;
    private long output;
    private long calls;
    private boolean inputComplete = true;
    private boolean outputComplete = true;

    public Map<String, Object> record(Map<String, Object> measured) {
        calls++;
        Object in = measured.get("inputTokens");
        Object out = measured.get("outputTokens");
        if (in instanceof Number n && n.longValue() >= 0) input += n.longValue();
        else inputComplete = false;
        if (out instanceof Number n && n.longValue() >= 0) output += n.longValue();
        else outputComplete = false;
        Map<String, Object> snapshot = new LinkedHashMap<>(measured);
        snapshot.put("calls", calls);
        snapshot.put("inputComplete", inputComplete);
        snapshot.put("outputComplete", outputComplete);
        if (inputComplete) snapshot.put("totalInputTokens", input);
        if (outputComplete) snapshot.put("totalOutputTokens", output);
        return snapshot;
    }
}
