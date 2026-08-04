package com.jarvis.memory;

import com.jarvis.common.dto.ChatRequest;
import com.jarvis.common.dto.ChatResponse;
import com.jarvis.common.event.CognitiveEvent;
import com.jarvis.common.event.CognitiveEventBus;
import com.jarvis.memory.pipeline.CognitivePipelineExecutor;
import com.jarvis.memory.pipeline.PipelineContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Conversation service that delegates chat execution to the cognitive pipeline.
 */
@Service
public class InMemoryConversationService implements ConversationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(InMemoryConversationService.class);

    private final CognitiveEventBus cognitiveEventBus;
    private final CognitivePipelineExecutor pipelineExecutor;

    /**
     * Creates the conversation service.
     *
     * @param cognitiveEventBus cognitive event bus
     * @param pipelineExecutor cognitive pipeline executor
     */
    public InMemoryConversationService(
            CognitiveEventBus cognitiveEventBus,
            CognitivePipelineExecutor pipelineExecutor
    ) {
        this.cognitiveEventBus = cognitiveEventBus;
        this.pipelineExecutor = pipelineExecutor;
    }

    /**
     * Processes a user chat request within a conversation.
     *
     * @param request user chat request
     * @return generated chat response
     */
    @Override
    public ChatResponse chat(ChatRequest request) {
        String conversationId = normalizeConversationId(request.conversationId());
        String requestId = UUID.randomUUID().toString();
        cognitiveEventBus.startRequest(requestId, conversationId, event -> { });
        try {
            LOGGER.info("[JARVIS] Incoming pipeline request. Conversation id: {}", conversationId);
            return pipelineExecutor.execute(PipelineContext.initial(
                    conversationId,
                    new ChatRequest(conversationId, request.message()),
                    event -> { }
            ));
        } finally {
            cognitiveEventBus.finishRequest();
        }
    }

    /**
     * Processes a user chat request within a conversation and streams lifecycle events.
     *
     * @param request user chat request
     * @param eventSink event sink
     */
    @Override
    public void stream(ChatRequest request, Consumer<CognitiveEvent> eventSink) {
        String conversationId = normalizeConversationId(request.conversationId());
        String requestId = UUID.randomUUID().toString();
        cognitiveEventBus.startRequest(requestId, conversationId, eventSink);
        try {
            LOGGER.info("[JARVIS] Incoming streaming pipeline request. Conversation id: {}", conversationId);
            pipelineExecutor.execute(PipelineContext.initial(
                    conversationId,
                    new ChatRequest(conversationId, request.message()),
                    event -> { }
            ));
        } finally {
            cognitiveEventBus.finishRequest();
        }
    }

    private String normalizeConversationId(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return conversationId;
    }
}
