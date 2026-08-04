package com.jarvis.memory.pipeline;

import com.jarvis.common.event.CognitiveEventBus;
import com.jarvis.common.event.CognitiveEventType;
import com.jarvis.common.memory.ConversationMessage;
import com.jarvis.common.memory.MessageRole;
import com.jarvis.memory.ConversationMemoryService;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Validates the incoming request and loads conversation state.
 */
@Service
@Order(10)
public class ValidationStage implements PipelineStage {

    private final ConversationMemoryService memoryService;
    private final CognitiveEventBus cognitiveEventBus;

    /**
     * Creates the validation stage.
     *
     * @param memoryService memory service
     * @param cognitiveEventBus event bus
     */
    public ValidationStage(ConversationMemoryService memoryService, CognitiveEventBus cognitiveEventBus) {
        this.memoryService = memoryService;
        this.cognitiveEventBus = cognitiveEventBus;
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
        cognitiveEventBus.publish(CognitiveEventType.REQUEST_RECEIVED, "RECEIVED", "Incoming request", null, Map.of(
                "messageLength", message.length()
        ));
        Instant lookupStartedAt = Instant.now();
        var conversation = memoryService.getMessages(context.conversationId());
        long lookupLatencyMs = Duration.between(lookupStartedAt, Instant.now()).toMillis();
        memoryService.addMessage(context.conversationId(), new ConversationMessage(MessageRole.USER, message, Instant.now()));
        return context.withConversation(conversation).withMetadata("conversationLookupTimeMs", lookupLatencyMs);
    }
}
