package com.jarvis.core.pipeline;

import com.jarvis.common.dto.ChatRequest;
import com.jarvis.common.event.ChatEventSink;
import com.jarvis.common.event.CognitiveEvent;
import com.jarvis.common.event.CognitiveEventBus;
import com.jarvis.memory.pipeline.PipelineContext;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Creates request-scoped cognitive pipeline contexts.
 */
@Service
public class PipelineContextFactory {

    private final CognitiveEventBus cognitiveEventBus;

    /**
     * Creates the context factory.
     *
     * @param cognitiveEventBus cognitive event bus
     */
    public PipelineContextFactory(CognitiveEventBus cognitiveEventBus) {
        this.cognitiveEventBus = cognitiveEventBus;
    }

    /**
     * Creates a pipeline context for a normal chat request.
     *
     * @param request chat request
     * @return pipeline context
     */
    public PipelineContext create(ChatRequest request) {
        return create(request, event -> { });
    }

    /**
     * Creates a pipeline context for a streaming chat request.
     *
     * @param request chat request
     * @param eventSink cognitive event sink
     * @return pipeline context
     */
    public PipelineContext create(ChatRequest request, Consumer<CognitiveEvent> eventSink) {
        String conversationId = normalizeConversationId(request.conversationId());
        String requestId = UUID.randomUUID().toString();
        cognitiveEventBus.startRequest(requestId, conversationId, eventSink);
        ChatEventSink modelEventSink = event -> { };
        return PipelineContext.initial(
                conversationId,
                new ChatRequest(conversationId, request.message()),
                modelEventSink
        );
    }

    /**
     * Finishes the current request context.
     */
    public void finish() {
        cognitiveEventBus.finishRequest();
    }

    private String normalizeConversationId(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return conversationId;
    }
}
