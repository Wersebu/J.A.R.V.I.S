package com.jarvis.core.diagnostics;

import com.jarvis.common.diagnostics.InferenceDiagnostics;
import com.jarvis.common.diagnostics.InferenceDiagnosticsService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * In-memory bounded diagnostics history.
 */
@Service
@EnableConfigurationProperties(DiagnosticsProperties.class)
public class InMemoryInferenceDiagnosticsService implements InferenceDiagnosticsService {

    private final DiagnosticsProperties properties;
    private final Map<UUID, InferenceDiagnostics> history;
    private UUID latestRequestId;

    /**
     * Creates the diagnostics service.
     *
     * @param properties diagnostics properties
     */
    public InMemoryInferenceDiagnosticsService(DiagnosticsProperties properties) {
        this.properties = properties;
        this.history = new LinkedHashMap<>();
    }

    @Override
    public synchronized InferenceDiagnostics create(UUID requestId, String conversationId) {
        InferenceDiagnostics diagnostics = new InferenceDiagnostics(requestId, conversationId);
        if (properties.enabled()) {
            history.put(requestId, diagnostics);
            latestRequestId = requestId;
            trim();
        }
        return diagnostics;
    }

    @Override
    public synchronized Optional<InferenceDiagnostics> latest() {
        return latestRequestId == null ? Optional.empty() : Optional.ofNullable(history.get(latestRequestId));
    }

    @Override
    public synchronized Optional<InferenceDiagnostics> find(UUID requestId) {
        return Optional.ofNullable(history.get(requestId));
    }

    private void trim() {
        while (history.size() > properties.requestHistorySize()) {
            UUID first = history.keySet().iterator().next();
            history.remove(first);
        }
    }
}
