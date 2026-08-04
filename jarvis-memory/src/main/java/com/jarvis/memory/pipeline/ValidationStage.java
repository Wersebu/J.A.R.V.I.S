package com.jarvis.memory.pipeline;

import com.jarvis.common.memory.ConversationMessage;
import com.jarvis.common.memory.MessageRole;
import com.jarvis.memory.ConversationMemoryService;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * Validates the incoming request and loads conversation state.
 */
@Service
@Order(10)
public class ValidationStage implements PipelineStage {

    private final ConversationMemoryService memoryService;

    /**
     * Creates the validation stage.
     *
     * @param memoryService memory service
     * @param cognitiveEventBus event bus
     */
    public ValidationStage(ConversationMemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @Override
    public String name() {
        return "ValidationStage";
    }

    @Override
    public PipelineContext execute(PipelineContext context) {
        String message = context.request().message();
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("Message must not be empty");
        }
        Instant lookupStartedAt = Instant.now();
        var conversation = memoryService.getMessages(context.conversationId());
        long lookupLatencyMs = Duration.between(lookupStartedAt, Instant.now()).toMillis();
        memoryService.addMessage(context.conversationId(), new ConversationMessage(MessageRole.USER, message, Instant.now()));
        return context.withConversation(conversation).withMetadata("conversationLookupTimeMs", lookupLatencyMs);
    }
}
