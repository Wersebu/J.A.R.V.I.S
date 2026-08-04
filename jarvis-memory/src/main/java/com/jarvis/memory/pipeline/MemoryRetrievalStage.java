package com.jarvis.memory.pipeline;

import com.jarvis.common.event.CognitiveEventBus;
import com.jarvis.common.event.CognitiveEventType;
import com.jarvis.memory.cognitive.CognitiveMemoryService;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Retrieves relevant cognitive memories before knowledge retrieval.
 */
@Service
@Order(25)
public class MemoryRetrievalStage implements PipelineStage {

    private final CognitiveMemoryService memoryService;
    private final CognitiveEventBus cognitiveEventBus;

    /**
     * Creates the memory retrieval stage.
     *
     * @param memoryService cognitive memory service
     * @param cognitiveEventBus event bus
     */
    public MemoryRetrievalStage(CognitiveMemoryService memoryService, CognitiveEventBus cognitiveEventBus) {
        this.memoryService = memoryService;
        this.cognitiveEventBus = cognitiveEventBus;
    }

    @Override
    public String name() {
        return "MemoryRetrievalStage";
    }

    @Override
    public PipelineContext execute(PipelineContext context) {
        cognitiveEventBus.publish(CognitiveEventType.MEMORY_SEARCH_STARTED, "SEARCHING", "Searching cognitive memory", null, Map.of(
                "query", context.request().message()
        ));
        Instant startedAt = Instant.now();
        var memoryContext = memoryService.retrieveContext(context.request().message());
        long durationMs = Duration.between(startedAt, Instant.now()).toMillis();
        if (memoryContext.memoryCount() == 0) {
            cognitiveEventBus.publish(CognitiveEventType.MEMORY_SKIPPED, "SKIPPED", "No relevant memories found", null, Map.of(
                    "query", context.request().message(),
                    "executionTimeMs", durationMs
            ));
        } else {
            memoryContext.memories().forEach(memory -> cognitiveEventBus.publish(
                    CognitiveEventType.MEMORY_FOUND,
                    "FOUND",
                    "Memory found",
                    "memory:" + memory.id(),
                    Map.of(
                            "type", memory.type().name(),
                            "title", memory.title(),
                            "confidence", memory.confidence()
                    )
            ));
        }
        return context.withMemoryContext(memoryContext)
                .withMetadata("memoryRetrievalTimeMs", durationMs)
                .withMetadata("memoriesUsed", memoryContext.memoryCount());
    }
}
