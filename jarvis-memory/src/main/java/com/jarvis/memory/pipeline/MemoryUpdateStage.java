package com.jarvis.memory.pipeline;

import com.jarvis.common.event.CognitiveEventBus;
import com.jarvis.common.event.CognitiveEventType;
import com.jarvis.memory.cognitive.CognitiveMemoryService;
import com.jarvis.memory.cognitive.MemoryMutationType;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Updates cognitive memory after model execution.
 */
@Service
@Order(95)
public class MemoryUpdateStage implements PipelineStage {

    private final CognitiveMemoryService memoryService;
    private final CognitiveEventBus cognitiveEventBus;

    /**
     * Creates the memory update stage.
     *
     * @param memoryService cognitive memory service
     * @param cognitiveEventBus event bus
     */
    public MemoryUpdateStage(CognitiveMemoryService memoryService, CognitiveEventBus cognitiveEventBus) {
        this.memoryService = memoryService;
        this.cognitiveEventBus = cognitiveEventBus;
    }

    @Override
    public String name() {
        return "MemoryUpdateStage";
    }

    @Override
    public PipelineContext execute(PipelineContext context) {
        var mutations = memoryService.updateFromConversation(
                context.conversationId(),
                context.request().message(),
                context.response()
        );
        if (mutations.isEmpty()) {
            cognitiveEventBus.publish(CognitiveEventType.MEMORY_SKIPPED, "SKIPPED", "No memory updates", null, Map.of(
                    "conversationId", context.conversationId()
            ));
            return context.withMetadata("memoryMutations", 0);
        }
        mutations.forEach(mutation -> cognitiveEventBus.publish(
                mutation.type() == MemoryMutationType.CREATED
                        ? CognitiveEventType.MEMORY_CREATED
                        : mutation.type() == MemoryMutationType.UPDATED
                        ? CognitiveEventType.MEMORY_UPDATED
                        : CognitiveEventType.MEMORY_SKIPPED,
                mutation.type().name(),
                mutation.reason(),
                mutation.memory() == null ? null : "memory:" + mutation.memory().id(),
                mutation.memory() == null ? Map.of() : Map.of(
                        "type", mutation.memory().type().name(),
                        "title", mutation.memory().title(),
                        "confidence", mutation.memory().confidence()
                )
        ));
        return context.withMetadata("memoryMutations", mutations.size());
    }
}
