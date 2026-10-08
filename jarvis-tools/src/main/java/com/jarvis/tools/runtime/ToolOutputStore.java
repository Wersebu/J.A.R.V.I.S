package com.jarvis.tools.runtime;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Keeps full tool outputs that were too large for the model's context (or were later elided from
 * the conversation history), so the model can page through them with the {@code output} tool
 * instead of silently working from a truncated view.
 */
@Service
public class ToolOutputStore {

    private static final int MAX_ENTRIES = 300;

    private final Map<String, String> outputs = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    /**
     * Stores an output.
     *
     * @param text full text
     * @return id to pass to {@code output__read}
     */
    public synchronized String put(String text) {
        String id = "out-" + UUID.randomUUID().toString().substring(0, 8);
        outputs.put(id, text == null ? "" : text);
        return id;
    }

    /**
     * Returns a stored output.
     *
     * @param id output id
     * @return text, when still stored
     */
    public synchronized Optional<String> get(String id) {
        return Optional.ofNullable(outputs.get(id == null ? "" : id.strip()));
    }
}
