package com.jarvis.memory;

import com.jarvis.brain.BrainRouter;
import com.jarvis.common.ai.AIProvider;
import com.jarvis.common.ai.AIProviderException;
import com.jarvis.common.ai.Brain;
import com.jarvis.common.dto.ChatRequest;
import com.jarvis.common.dto.ChatResponse;
import com.jarvis.common.memory.ConversationMessage;
import com.jarvis.common.memory.MessageRole;
import com.jarvis.common.prompt.PromptBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * In-memory conversation orchestration service for version 0.2.
 */
@Service
public class InMemoryConversationService implements ConversationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(InMemoryConversationService.class);

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
        String conversationId = normalizeConversationId(request.conversationId());
        LOGGER.info("[JARVIS] Conversation id: {}", conversationId);

        memoryService.addMessage(conversationId, new ConversationMessage(MessageRole.USER, request.message(), Instant.now()));

        ChatRequest normalizedRequest = new ChatRequest(conversationId, request.message());
        String prompt = promptBuilder.buildPrompt(normalizedRequest);
        Brain brain = brainRouter.select(normalizedRequest);
        ChatResponse response = selectProvider(brain).chat(brain, prompt);

        memoryService.addMessage(conversationId, new ConversationMessage(MessageRole.ASSISTANT, response.response(), Instant.now()));
        return response;
    }

    private AIProvider selectProvider(Brain brain) {
        return aiProviders.stream()
                .filter(provider -> provider.provider().equalsIgnoreCase(brain.provider()))
                .findFirst()
                .orElseThrow(() -> new AIProviderException("AI provider is not available: " + brain.provider()));
    }

    private String normalizeConversationId(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return conversationId;
    }
}
