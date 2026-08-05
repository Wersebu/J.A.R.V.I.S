package com.jarvis.memory.pipeline;

import com.jarvis.common.event.CognitiveEventBus;
import com.jarvis.common.event.CognitiveEventType;
import com.jarvis.memory.cognitive.CognitiveMemoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger LOGGER = LoggerFactory.getLogger(MemoryRetrievalStage.class);

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
            cognitiveEventBus.publish(CognitiveEventType.MEMORY_NOT_FOUND, "NOT_FOUND", "No relevant memories found", null, Map.of(
                    "query", context.request().message(),
                    "executionTimeMs", durationMs
            ));
            LOGGER.info("""
                    [JARVIS]
                    MEMORY RETRIEVAL FINISHED

                    Result:
                    No relevant memories found

                    Execution time:
                    {} ms
                    """, durationMs);
        } else {
            memoryContext.memories().forEach(memory -> cognitiveEventBus.publish(
                    CognitiveEventType.MEMORY_CANDIDATE_FOUND,
                    "CANDIDATE",
                    "Memory candidate found",
                    memoryNodeId(memory),
                    Map.of(
                            "id", memoryNodeId(memory),
                            "memoryId", memory.id().toString(),
                            "type", memory.type().name(),
                            "title", memory.title(),
                            "confidence", memory.confidence()
                    )
            ));
            cognitiveEventBus.publish(CognitiveEventType.MEMORY_INJECTED, "INJECTED", "Memory injected into pipeline context", null, Map.of(
                    "memories", memoryContext.memoryCount(),
                    "characters", memoryContext.totalCharacters(),
                    "estimatedTokens", memoryContext.estimatedTokens(),
                    "executionTimeMs", durationMs
            ));
            memoryContext.memories().forEach(memory -> cognitiveEventBus.publish(
                    CognitiveEventType.MEMORY_INJECTED,
                    "USED",
                    "Memory used by prompt context",
                    memoryNodeId(memory),
                    Map.of(
                            "id", memoryNodeId(memory),
                            "memoryId", memory.id().toString(),
                            "type", memory.type().name(),
                            "title", memory.title(),
                            "confidence", memory.confidence()
                    )
            ));
            LOGGER.info("""
                    [JARVIS]
                    MEMORY RETRIEVAL FINISHED

                    Injected memories:
                    {}

                    Characters:
                    {}

                    PipelineContext.memory:
                    populated
                    """, memoryContext.memoryCount(), memoryContext.totalCharacters());
        }
        return context.withMemoryContext(memoryContext)
                .withMetadata("memoryRetrievalTimeMs", durationMs)
                .withMetadata("memoriesUsed", memoryContext.memoryCount());
    }

    private String memoryNodeId(com.jarvis.common.memory.MemoryRecord memory) {
        return "memory-record:" + memory.id();
    }
}
