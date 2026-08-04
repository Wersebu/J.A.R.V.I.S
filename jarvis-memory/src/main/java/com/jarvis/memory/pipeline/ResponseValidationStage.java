package com.jarvis.memory.pipeline;

import com.jarvis.common.event.CognitiveEventBus;
import com.jarvis.common.event.CognitiveEventType;
import com.jarvis.common.memory.ConversationMessage;
import com.jarvis.common.memory.MessageRole;
import com.jarvis.memory.ConversationMemoryService;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

/**
 * Performs basic response validation.
 */
@Service
@Order(100)
public class ResponseValidationStage implements PipelineStage {

    private final CognitiveEventBus cognitiveEventBus;
    private final ConversationMemoryService memoryService;

    /**
     * Creates the response validation stage.
     *
     * @param cognitiveEventBus event bus
     * @param memoryService memory service
     */
    public ResponseValidationStage(CognitiveEventBus cognitiveEventBus, ConversationMemoryService memoryService) {
        this.cognitiveEventBus = cognitiveEventBus;
        this.memoryService = memoryService;
    }

    @Override
    public String name() {
        return "ResponseValidationStage";
    }

    @Override
    public PipelineContext execute(PipelineContext context) {
        if (context.response() == null) {
            throw new IllegalStateException("Model response is null");
        }
        if (context.response().isBlank()) {
            throw new IllegalStateException("Model response is empty");
        }
        memoryService.addMessage(
                context.conversationId(),
                new ConversationMessage(MessageRole.ASSISTANT, context.response(), Instant.now())
        );
        var finishedEvent = context.generationFinishedEvent();
        cognitiveEventBus.publish(CognitiveEventType.REQUEST_FINISHED, "FINISHED", "Request finished", null, Map.ofEntries(
                Map.entry("generationTimeMs", finishedEvent == null ? 0 : finishedEvent.generationTimeMs()),
                Map.entry("retrievalTimeMs", context.retrievalResult() == null ? 0 : context.retrievalResult().executionTimeMs()),
                Map.entry("contextBuildTimeMs", context.knowledgeContext().buildTimeMs()),
                Map.entry("promptBuildTimeMs", context.metadata().getOrDefault("promptBuildTimeMs", 0L)),
                Map.entry("documentsUsed", context.knowledgeContext().sourceCount()),
                Map.entry("tokensGenerated", finishedEvent == null || finishedEvent.completionTokens() == null ? 0 : finishedEvent.completionTokens()),
                Map.entry("estimatedPromptTokens", context.metadata().getOrDefault("estimatedPromptTokens", 0)),
                Map.entry("taskType", context.executionPlan().taskType().name()),
                Map.entry("complexity", context.executionPlan().complexityScore()),
                Map.entry("reason", context.executionPlan().reason()),
                Map.entry("confidence", context.executionPlan().confidence())
        ));
        return context;
    }
}
