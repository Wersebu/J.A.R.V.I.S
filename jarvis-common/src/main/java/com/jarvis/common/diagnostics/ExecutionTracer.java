package com.jarvis.common.diagnostics;

import com.jarvis.common.ai.BrainType;
import com.jarvis.common.event.CognitiveEvent;
import com.jarvis.common.event.CognitiveEventType;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Request-scoped observer that records execution timing without affecting business logic.
 */
public interface ExecutionTracer {

    /**
     * Starts a new trace.
     *
     * @param traceId trace identifier
     * @param conversationId conversation identifier
     * @param sink live event sink
     */
    void start(String traceId, String conversationId, Consumer<CognitiveEvent> sink);

    /**
     * Records a cognitive event as an execution trace step when applicable.
     *
     * @param event source event
     * @param status event status
     * @param message event message
     * @param brain selected brain
     * @param model selected model
     * @param nodeId logical node id
     * @param metadata event metadata
     */
    void record(
            CognitiveEventType event,
            String status,
            String message,
            BrainType brain,
            String model,
            String nodeId,
            Map<String, Object> metadata
    );

    /**
     * Finishes the current trace.
     */
    void finish();
}
