package com.jarvis.memory;

import com.jarvis.brain.BrainRouter;
import com.jarvis.common.ai.AIProvider;
import com.jarvis.common.ai.AIProviderException;
import com.jarvis.common.ai.Brain;
import com.jarvis.common.dto.ChatRequest;
import com.jarvis.common.dto.ChatResponse;
import com.jarvis.common.event.BrainSelectedEvent;
import com.jarvis.common.event.ChatEventSink;
import com.jarvis.common.event.ErrorEvent;
import com.jarvis.common.event.PromptBuildingEvent;
import com.jarvis.common.event.RequestReceivedEvent;
import com.jarvis.common.memory.ConversationMessage;
import com.jarvis.common.memory.MessageRole;
import com.jarvis.common.prompt.PromptBuilder;
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
    private final List<AIProvider> aiProviders;

    /**
     * Creates the in-memory conversation service.
     *
     * @param memoryService conversation memory store
     * @param promptBuilder prompt builder
     * @param brainRouter brain router
     * @param aiProviders available AI providers
     */
    public InMemoryConversationService(
            ConversationMemoryService memoryService,
            PromptBuilder promptBuilder,
            BrainRouter brainRouter,
            List<AIProvider> aiProviders
    ) {
        this.memoryService = memoryService;
        this.promptBuilder = promptBuilder;
        this.brainRouter = brainRouter;
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
        ChatResponse response = selectProvider(context.brain()).chat(context.brain(), context.prompt());
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
            selectProvider(context.brain()).stream(context.conversationId(), context.brain(), context.prompt(), eventSink);
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
        Instant promptStartedAt = Instant.now();
        String prompt = promptBuilder.buildPrompt(normalizedRequest);
        long promptLatencyMs = Duration.between(promptStartedAt, Instant.now()).toMillis();
        LOGGER.info("[JARVIS] PromptBuilder latency: {} ms", promptLatencyMs);
        eventSink.publish(PromptBuildingEvent.create(conversationId, promptLatencyMs));

        Brain brain = brainRouter.select(normalizedRequest);
        eventSink.publish(BrainSelectedEvent.create(
                conversationId,
                brain.type(),
                brain.model(),
                brain.selectionReason(),
                brain.routerLatencyMs()
        ));
        return new ChatPipelineContext(conversationId, prompt, brain);
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
     */
    private record ChatPipelineContext(String conversationId, String prompt, Brain brain) {
    }
}
