package com.jarvis.core.service;

import com.jarvis.api.service.ChatService;
import com.jarvis.common.dto.ChatRequest;
import com.jarvis.common.dto.ChatResponse;
import com.jarvis.common.event.CognitiveEvent;
import com.jarvis.core.pipeline.PipelineContextFactory;
import com.jarvis.core.pipeline.ResponseMapper;
import com.jarvis.memory.pipeline.CognitivePipelineExecutor;
import com.jarvis.memory.pipeline.PipelineContext;
import org.springframework.stereotype.Service;

import java.util.function.Consumer;

/**
 * Chat entry service that invokes the cognitive pipeline as the only execution path.
 */
@Service
public class DefaultChatService implements ChatService {

    private final PipelineContextFactory pipelineContextFactory;
    private final CognitivePipelineExecutor pipelineExecutor;
    private final ResponseMapper responseMapper;

    /**
     * Creates the chat service.
     *
     * @param pipelineContextFactory pipeline context factory
     * @param pipelineExecutor pipeline executor
     * @param responseMapper response mapper
     */
    public DefaultChatService(
            PipelineContextFactory pipelineContextFactory,
            CognitivePipelineExecutor pipelineExecutor,
            ResponseMapper responseMapper
    ) {
        this.pipelineContextFactory = pipelineContextFactory;
        this.pipelineExecutor = pipelineExecutor;
        this.responseMapper = responseMapper;
    }

    /**
     * Processes a chat request through the cognitive pipeline.
     *
     * @param request chat request
     * @return generated chat response
     */
    @Override
    public ChatResponse chat(ChatRequest request) {
        var turn = reserveTurn(request);
        try {
            turn.await();
            var interactive = com.jarvis.common.run.InteractiveRequestContext.current();
            if (interactive != null) interactive.onRunning().run();
            PipelineContext context = pipelineContextFactory.create(request);
            var completed=com.jarvis.common.trace.PrivateInferenceContext.call(()->pipelineExecutor.execute(context));
            requireDelivered(completed);
            return responseMapper.toChatResponse(completed);
        } finally {
            try { pipelineContextFactory.finish(); }
            finally { if (com.jarvis.common.run.InteractiveRequestContext.current() == null) turn.close(); }
        }
    }

    private void requireDelivered(PipelineContext context){
        if(context.response()==null||context.response().isBlank()||!Boolean.TRUE.equals(context.metadata().get("assistantMessagePersisted"))||!Boolean.TRUE.equals(context.metadata().get("answerPublished")))throw new IllegalStateException("Request cannot succeed without a persisted and published answer");
    }

    private com.jarvis.common.run.ConversationTurns.Turn reserveTurn(ChatRequest request) {
        var current = com.jarvis.common.run.InteractiveRequestContext.current();
        return current != null ? current.turn() : com.jarvis.common.run.ConversationTurns.reserve(
                com.jarvis.common.auth.CurrentUserContext.requiredUserId(), request.conversationId());
    }

    /**
     * Streams a chat request through the cognitive pipeline.
     *
     * @param request chat request
     * @param eventSink event sink
     */
    @Override
    public void stream(ChatRequest request, Consumer<CognitiveEvent> eventSink) {
        var turn = reserveTurn(request);
        try {
            turn.await();
            var interactive = com.jarvis.common.run.InteractiveRequestContext.current();
            if (interactive != null) interactive.onRunning().run();
            PipelineContext context = pipelineContextFactory.create(request, eventSink);
            requireDelivered(com.jarvis.common.trace.PrivateInferenceContext.call(()->pipelineExecutor.execute(context)));
        } finally {
            try { pipelineContextFactory.finish(); }
            finally { if (com.jarvis.common.run.InteractiveRequestContext.current() == null) turn.close(); }
        }
    }
}
