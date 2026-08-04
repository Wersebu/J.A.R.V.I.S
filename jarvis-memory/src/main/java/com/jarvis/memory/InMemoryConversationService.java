package com.jarvis.memory;

import com.jarvis.brain.BrainRouter;
import com.jarvis.common.ai.AIProvider;
import com.jarvis.common.ai.AIProviderException;
import com.jarvis.common.ai.Brain;
import com.jarvis.brain.decision.ExecutionPlan;
import com.jarvis.common.context.KnowledgeContext;
import com.jarvis.common.context.KnowledgeUsage;
import com.jarvis.common.dto.ChatRequest;
import com.jarvis.common.dto.ChatResponse;
import com.jarvis.common.event.ChatEventSink;
import com.jarvis.common.event.CognitiveEvent;
import com.jarvis.common.event.CognitiveEventBus;
import com.jarvis.common.event.CognitiveEventType;
import com.jarvis.common.event.GenerationFinishedEvent;
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
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

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
    private final CognitiveEventBus cognitiveEventBus;
    private final List<AIProvider> aiProviders;

    /**
     * Creates the in-memory conversation service.
     *
     * @param memoryService conversation memory store
     * @param promptBuilder prompt builder
     * @param brainRouter brain router
     * @param knowledgeRetriever knowledge retriever
     * @param contextBuilder context builder
     * @param cognitiveEventBus cognitive event bus
     * @param aiProviders available AI providers
     */
    public InMemoryConversationService(
            ConversationMemoryService memoryService,
            PromptBuilder promptBuilder,
            BrainRouter brainRouter,
            KnowledgeRetriever knowledgeRetriever,
            ContextBuilder contextBuilder,
            CognitiveEventBus cognitiveEventBus,
            List<AIProvider> aiProviders
    ) {
        this.memoryService = memoryService;
        this.promptBuilder = promptBuilder;
        this.brainRouter = brainRouter;
        this.knowledgeRetriever = knowledgeRetriever;
        this.contextBuilder = contextBuilder;
        this.cognitiveEventBus = cognitiveEventBus;
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
        String conversationId = normalizeConversationId(request.conversationId());
        String requestId = UUID.randomUUID().toString();
        cognitiveEventBus.startRequest(requestId, conversationId, event -> { });
        try {
            ChatPipelineContext context = prepare(new ChatRequest(conversationId, request.message()));
            StringBuilder responseBuilder = new StringBuilder();
            ChatEventSink sink = event -> {
                if (event instanceof com.jarvis.common.event.TokenEvent tokenEvent) {
                    responseBuilder.append(tokenEvent.text());
                }
                if (event instanceof GenerationFinishedEvent finishedEvent) {
                    publishRequestFinished(context, finishedEvent);
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
        } catch (RuntimeException exception) {
            publishError(conversationId, exception);
            throw exception;
        } finally {
            cognitiveEventBus.finishRequest();
        }
    }

    /**
     * Processes a user chat request and streams events to the caller.
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
            ChatPipelineContext context = prepare(new ChatRequest(conversationId, request.message()));
            ChatEventSink legacySink = event -> {
                if (event instanceof GenerationFinishedEvent finishedEvent) {
                    publishRequestFinished(context, finishedEvent);
                }
            };
            selectProvider(context.brain()).stream(context.conversationId(), context.brain(), context.prompt(), legacySink);
        } catch (RuntimeException exception) {
            publishError(conversationId, exception);
            throw exception;
        } finally {
            cognitiveEventBus.finishRequest();
        }
    }

    private ChatPipelineContext prepare(ChatRequest request) {
        String conversationId = normalizeConversationId(request.conversationId());
        LOGGER.info("[JARVIS] Conversation id: {}", conversationId);
        cognitiveEventBus.publish(CognitiveEventType.REQUEST_RECEIVED, "RECEIVED", "Incoming request", null, Map.of(
                "messageLength", request.message() == null ? 0 : request.message().length()
        ));

        Instant lookupStartedAt = Instant.now();
        memoryService.getMessages(conversationId);
        long lookupLatencyMs = Duration.between(lookupStartedAt, Instant.now()).toMillis();
        LOGGER.info("[JARVIS] Conversation lookup: {} ms", lookupLatencyMs);

        memoryService.addMessage(conversationId, new ConversationMessage(MessageRole.USER, request.message(), Instant.now()));

        ChatRequest normalizedRequest = new ChatRequest(conversationId, request.message());

        cognitiveEventBus.publish(CognitiveEventType.BRAIN_ROUTING_STARTED, "ROUTING", "Selecting brain", null, Map.of());
        ExecutionPlan executionPlan = brainRouter.plan(normalizedRequest);
        publishExecutionPlanEvents(executionPlan);
        Brain brain = brainRouter.select(executionPlan);
        cognitiveEventBus.updateBrain(brain.type(), brain.model());
        cognitiveEventBus.publish(CognitiveEventType.BRAIN_SELECTED, "SELECTED", "Brain selected", "brain:" + brain.type(), Map.of(
                "brain", brain.type().name(),
                "model", brain.model(),
                "reason", brain.selectionReason(),
                "latencyMs", brain.routerLatencyMs(),
                "taskType", executionPlan.taskType().name(),
                "complexity", executionPlan.complexityScore(),
                "confidence", executionPlan.confidence(),
                "estimatedPromptTokens", executionPlan.estimatedPromptTokens(),
                "knowledgeRequired", executionPlan.knowledgeRequired(),
                "estimatedKnowledgeDocuments", executionPlan.estimatedKnowledgeDocuments()
        ));

        RetrievalResult retrievalResult = knowledgeRetriever.retrieve(normalizedRequest.message());
        KnowledgeContext knowledgeContext = contextBuilder.build(retrievalResult);

        cognitiveEventBus.publish(CognitiveEventType.PROMPT_BUILD_STARTED, "BUILDING", "Building prompt", null, Map.of(
                "documentsUsed", knowledgeContext.sourceCount()
        ));
        Instant promptStartedAt = Instant.now();
        String prompt = promptBuilder.buildPrompt(normalizedRequest, knowledgeContext);
        long promptLatencyMs = Duration.between(promptStartedAt, Instant.now()).toMillis();
        LOGGER.info("[JARVIS] PromptBuilder latency: {} ms", promptLatencyMs);
        cognitiveEventBus.publish(CognitiveEventType.PROMPT_BUILD_FINISHED, "FINISHED", "Prompt built", null, Map.of(
                "promptBuildTimeMs", promptLatencyMs,
                "promptCharacters", prompt.length(),
                "estimatedPromptTokens", prompt.length() / 4
        ));

        return new ChatPipelineContext(
                conversationId,
                prompt,
                brain,
                knowledgeContext,
                retrievalResult.executionTimeMs(),
                knowledgeContext.buildTimeMs(),
                promptLatencyMs,
                prompt.length() / 4,
                executionPlan
        );
    }

    private void publishExecutionPlanEvents(ExecutionPlan executionPlan) {
        cognitiveEventBus.publish(CognitiveEventType.TASK_ANALYZED, "ANALYZED", "Task analyzed", null, Map.of(
                "taskType", executionPlan.taskType().name(),
                "confidence", executionPlan.confidence(),
                "reason", executionPlan.reason()
        ));
        cognitiveEventBus.publish(CognitiveEventType.COMPLEXITY_ANALYZED, "ANALYZED", "Complexity analyzed", null, Map.of(
                "complexity", executionPlan.complexityScore(),
                "reason", executionPlan.reason()
        ));
        cognitiveEventBus.publish(CognitiveEventType.KNOWLEDGE_ANALYZED, "ANALYZED", "Knowledge requirements analyzed", null, Map.of(
                "knowledgeRequired", executionPlan.knowledgeRequired(),
                "estimatedKnowledgeDocuments", executionPlan.estimatedKnowledgeDocuments(),
                "reason", executionPlan.reason()
        ));
        cognitiveEventBus.publish(CognitiveEventType.EXECUTION_PLAN_CREATED, "CREATED", "Execution plan created", null, Map.of(
                "taskType", executionPlan.taskType().name(),
                "confidence", executionPlan.confidence(),
                "complexity", executionPlan.complexityScore(),
                "knowledgeRequired", executionPlan.knowledgeRequired(),
                "estimatedKnowledgeDocuments", executionPlan.estimatedKnowledgeDocuments(),
                "estimatedPromptTokens", executionPlan.estimatedPromptTokens(),
                "brain", executionPlan.selectedBrain().name(),
                "model", executionPlan.selectedModel(),
                "reason", executionPlan.reason()
        ));
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

    private void publishRequestFinished(ChatPipelineContext context, GenerationFinishedEvent finishedEvent) {
        cognitiveEventBus.publish(CognitiveEventType.REQUEST_FINISHED, "FINISHED", "Request finished", null, Map.ofEntries(
                Map.entry("generationTimeMs", finishedEvent.generationTimeMs()),
                Map.entry("retrievalTimeMs", context.retrievalTimeMs()),
                Map.entry("contextBuildTimeMs", context.contextBuildTimeMs()),
                Map.entry("promptBuildTimeMs", context.promptBuildTimeMs()),
                Map.entry("documentsUsed", context.knowledgeContext().sourceCount()),
                Map.entry("tokensGenerated", finishedEvent.completionTokens() == null ? 0 : finishedEvent.completionTokens()),
                Map.entry("estimatedPromptTokens", context.estimatedPromptTokens()),
                Map.entry("taskType", context.executionPlan().taskType().name()),
                Map.entry("complexity", context.executionPlan().complexityScore()),
                Map.entry("reason", context.executionPlan().reason()),
                Map.entry("confidence", context.executionPlan().confidence())
        ));
    }

    private void publishError(String conversationId, RuntimeException exception) {
        LOGGER.error("[JARVIS] Conversation processing error", exception);
        cognitiveEventBus.error("Request processing failed", Map.of(
                "conversationId", conversationId,
                "exception", exception.getClass().getSimpleName(),
                "message", exception.getMessage() == null ? "" : exception.getMessage()
        ));
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
    private record ChatPipelineContext(
            String conversationId,
            String prompt,
            Brain brain,
            KnowledgeContext knowledgeContext,
            long retrievalTimeMs,
            long contextBuildTimeMs,
            long promptBuildTimeMs,
            int estimatedPromptTokens,
            ExecutionPlan executionPlan
    ) {
    }
}
