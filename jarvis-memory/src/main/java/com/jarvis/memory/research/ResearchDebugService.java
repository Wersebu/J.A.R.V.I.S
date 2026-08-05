package com.jarvis.memory.research;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Stores recent research contexts for diagnostics.
 */
@Service
public class ResearchDebugService {

    private static final int MAX_CONTEXTS = 50;
    private final Map<String, ResearchContext> contexts = new LinkedHashMap<>();

    /**
     * Stores a context snapshot reference.
     *
     * @param context research context
     */
    public synchronized void put(ResearchContext context) {
        contexts.put(context.requestId(), context);
        while (contexts.size() > MAX_CONTEXTS) {
            String first = contexts.keySet().iterator().next();
            contexts.remove(first);
        }
    }

    /**
     * Finds a research context by request id.
     *
     * @param requestId request id
     * @return context
     */
    public synchronized Optional<ResearchContext> find(String requestId) {
        return Optional.ofNullable(contexts.get(requestId));
    }
}
