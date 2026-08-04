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
        try {
            PipelineContext context = pipelineContextFactory.create(request);
            return responseMapper.toChatResponse(pipelineExecutor.execute(context));
        } finally {
            pipelineContextFactory.finish();
        }
    }

    /**
     * Streams a chat request through the cognitive pipeline.
     *
     * @param request chat request
     * @param eventSink event sink
     */
    @Override
    public void stream(ChatRequest request, Consumer<CognitiveEvent> eventSink) {
        try {
            PipelineContext context = pipelineContextFactory.create(request, eventSink);
            PipelineContext completedContext = pipelineExecutor.execute(context);
            completedContext.memoryAgentFuture().join();
        } finally {
            pipelineContextFactory.finish();
        }
    }
}
