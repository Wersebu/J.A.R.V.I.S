package com.jarvis.tools.runtime;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Stores recent native tool runtime snapshots for diagnostics.
 */
@Service
public class ToolRuntimeDebugService {

    private static final int MAX_SNAPSHOTS = 100;
    private final Map<String, ToolRuntimeSnapshot> snapshots = new LinkedHashMap<>();

    /**
     * Stores a snapshot.
     *
     * @param snapshot snapshot
     */
    public synchronized void save(ToolRuntimeSnapshot snapshot) {
        if (snapshot == null || snapshot.requestId() == null || snapshot.requestId().isBlank()) {
            return;
        }
        snapshots.put(snapshot.requestId(), snapshot);
        while (snapshots.size() > MAX_SNAPSHOTS) {
            String first = snapshots.keySet().iterator().next();
            snapshots.remove(first);
        }
    }

    /**
     * Finds a snapshot.
     *
     * @param requestId request identifier
     * @return snapshot
     */
    public synchronized Optional<ToolRuntimeSnapshot> find(String requestId) {
        return Optional.ofNullable(snapshots.get(requestId));
    }
}
