package com.jarvis.memory;

import com.jarvis.brain.BrainRouter;
import com.jarvis.common.ai.AIProvider;
import com.jarvis.common.ai.AIProviderException;
import com.jarvis.common.ai.Brain;
import com.jarvis.common.context.KnowledgeContext;
import com.jarvis.common.context.KnowledgeUsage;
import com.jarvis.common.dto.ChatRequest;
import com.jarvis.common.dto.ChatResponse;
import com.jarvis.common.event.BrainSelectedEvent;
import com.jarvis.common.event.ChatEventSink;
import com.jarvis.common.event.GenerationFinishedEvent;
import com.jarvis.common.event.KnowledgeUsageEvent;
import com.jarvis.common.event.ErrorEvent;
import com.jarvis.common.event.PromptBuildingEvent;
import com.jarvis.common.event.RequestReceivedEvent;
import com.jarvis.common.memory.ConversationMessage;
import com.jarvis.common.memory.MessageRole;
import com.jarvis.common.prompt.PromptBuilder;
import com.jarvis.knowledge.context.ContextBuilder;
import com.jarvis.knowledge.retrieval.KnowledgeRetriever;
import com.jarvis.knowledge.retrieval.RetrievalResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * In-memory conversation orchestration service for version 0.2.
 */
@Service
public class InMemoryConversationService implements ConversationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(InMemoryConversationService.class);
    private static final ChatEventSink NO_OP_EVENT_SINK = event -> {
    };

    private final ConversationMemoryService memoryService;
    private final PromptBuilder promptBuilder;
    private final BrainRouter brainRouter;
    private final KnowledgeRetriever knowledgeRetriever;
    private final ContextBuilder contextBuilder;
    private final List<AIProvider> aiProviders;

    /**
     * Creates the in-memory conversation service.
     *
     * @param memoryService conversation memory store
     * @param promptBuilder prompt builder
     * @param brainRouter brain router
     * @param knowledgeRetriever knowledge retriever
     * @param contextBuilder context builder
     * @param aiProviders available AI providers
     */
    public InMemoryConversationService(
            ConversationMemoryService memoryService,
            PromptBuilder promptBuilder,
            BrainRouter brainRouter,
            KnowledgeRetriever knowledgeRetriever,
            ContextBuilder contextBuilder,
            List<AIProvider> aiProviders
    ) {
        this.memoryService = memoryService;
        this.promptBuilder = promptBuilder;
        this.brainRouter = brainRouter;
        this.knowledgeRetriever = knowledgeRetriever;
        this.contextBuilder = contextBuilder;
        this.aiProviders = List.copyOf(aiProviders);
    }

    /**
     * Stores the user message, builds the prompt, selects a brain, calls the AI provider, and stores the response.
     *
     * @param request user chat request
     * @return generated chat response
     */
    @Override
    public ChatResponse chat(ChatRequest request) {
        ChatPipelineContext context = prepare(request, NO_OP_EVENT_SINK);
        StringBuilder responseBuilder = new StringBuilder();
        ChatEventSink sink = event -> {
            if (event instanceof com.jarvis.common.event.TokenEvent tokenEvent) {
                responseBuilder.append(tokenEvent.text());
            }
            if (event instanceof GenerationFinishedEvent finishedEvent) {
                LOGGER.info("[JARVIS] Knowledge usage: {}", usage(context, finishedEvent.generationTimeMs()));
            }
        };
        selectProvider(context.brain()).stream(context.conversationId(), context.brain(), context.prompt(), sink);
        ChatResponse response = new ChatResponse(responseBuilder.toString());
        memoryService.addMessage(
                context.conversationId(),
                new ConversationMessage(MessageRole.ASSISTANT, response.response(), Instant.now())
        );
        return response;
    }

    /**
     * Processes a user chat request and streams events to the caller.
     *
     * @param request user chat request
     * @param eventSink event sink
     */
    @Override
    public void stream(ChatRequest request, ChatEventSink eventSink) {
        String conversationId = normalizeConversationId(request.conversationId());
        try {
            ChatPipelineContext context = prepare(new ChatRequest(conversationId, request.message()), eventSink);
            ChatEventSink knowledgeUsageSink = event -> {
                eventSink.publish(event);
                if (event instanceof GenerationFinishedEvent finishedEvent && context.knowledgeContext().sourceCount() > 0) {
                    eventSink.publish(KnowledgeUsageEvent.create(usage(context, finishedEvent.generationTimeMs())));
                }
            };
            selectProvider(context.brain()).stream(context.conversationId(), context.brain(), context.prompt(), knowledgeUsageSink);
        } catch (RuntimeException exception) {
            publishError(eventSink, conversationId, exception);
            throw exception;
        }
    }

    private ChatPipelineContext prepare(ChatRequest request, ChatEventSink eventSink) {
        String conversationId = normalizeConversationId(request.conversationId());
        LOGGER.info("[JARVIS] Conversation id: {}", conversationId);
        eventSink.publish(RequestReceivedEvent.create(conversationId));

        Instant lookupStartedAt = Instant.now();
        memoryService.getMessages(conversationId);
        long lookupLatencyMs = Duration.between(lookupStartedAt, Instant.now()).toMillis();
        LOGGER.info("[JARVIS] Conversation lookup: {} ms", lookupLatencyMs);

        memoryService.addMessage(conversationId, new ConversationMessage(MessageRole.USER, request.message(), Instant.now()));

        ChatRequest normalizedRequest = new ChatRequest(conversationId, request.message());

        Brain brain = brainRouter.select(normalizedRequest);
        eventSink.publish(BrainSelectedEvent.create(
                conversationId,
                brain.type(),
                brain.model(),
                brain.selectionReason(),
                brain.routerLatencyMs()
        ));

        RetrievalResult retrievalResult = knowledgeRetriever.retrieve(normalizedRequest.message());
        KnowledgeContext knowledgeContext = contextBuilder.build(retrievalResult);

        Instant promptStartedAt = Instant.now();
        String prompt = promptBuilder.buildPrompt(normalizedRequest, knowledgeContext);
        long promptLatencyMs = Duration.between(promptStartedAt, Instant.now()).toMillis();
        LOGGER.info("[JARVIS] PromptBuilder latency: {} ms", promptLatencyMs);
        eventSink.publish(PromptBuildingEvent.create(conversationId, promptLatencyMs));

        return new ChatPipelineContext(conversationId, prompt, brain, knowledgeContext);
    }

    private KnowledgeUsage usage(ChatPipelineContext context, long generationTimeMs) {
        return KnowledgeUsage.from(context.conversationId(), context.knowledgeContext(), generationTimeMs);
    }

    private AIProvider selectProvider(Brain brain) {
        return aiProviders.stream()
                .filter(provider -> provider.provider().equalsIgnoreCase(brain.provider()))
                .findFirst()
                .orElseThrow(() -> new AIProviderException("AI provider is not available: " + brain.provider()));
    }

    private void publishError(ChatEventSink eventSink, String conversationId, RuntimeException exception) {
        LOGGER.error("[JARVIS] Conversation processing error", exception);
        eventSink.publish(ErrorEvent.create(conversationId, "Request processing failed"));
    }

    private String normalizeConversationId(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return conversationId;
    }

    /**
     * Prepared chat pipeline state.
     *
     * @param conversationId conversation identifier
     * @param prompt prepared prompt
     * @param brain selected brain
     * @param knowledgeContext injected knowledge context
     */
    private record ChatPipelineContext(String conversationId, String prompt, Brain brain, KnowledgeContext knowledgeContext) {
    }
}
