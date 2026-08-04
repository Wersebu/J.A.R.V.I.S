package com.jarvis.common.diagnostics;

import java.util.Optional;
import java.util.UUID;

/**
 * Stores recent inference diagnostics for debugging latency.
 */
public interface InferenceDiagnosticsService {

    /**
     * Creates diagnostics for a request.
     *
     * @param requestId request id
     * @param conversationId conversation id
     * @return diagnostics
     */
    InferenceDiagnostics create(UUID requestId, String conversationId);

    /**
     * Returns the latest diagnostics.
     *
     * @return latest diagnostics
     */
    Optional<InferenceDiagnostics> latest();

    /**
     * Finds diagnostics by request id.
     *
     * @param requestId request id
     * @return diagnostics
     */
    Optional<InferenceDiagnostics> find(UUID requestId);
}
