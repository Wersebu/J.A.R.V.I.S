package com.jarvis.api.controller;

import com.jarvis.common.memory.ConversationMessage;
import com.jarvis.memory.ConversationMemoryService;
import com.jarvis.memory.conversation.ConversationContextReducer;
import com.jarvis.memory.conversation.ConversationHistoryProperties;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * REST controller for short-term conversation continuity.
 */
@RestController
public class ConversationController {

    private final ConversationMemoryService conversationMemoryService;
    private final ConversationContextReducer contextReducer;
    private final ConversationHistoryProperties properties;

    /**
     * Creates the conversation controller.
     *
     * @param conversationMemoryService conversation memory service
     * @param contextReducer conversation context reducer
     * @param properties conversation history properties
     */
    public ConversationController(
            ConversationMemoryService conversationMemoryService,
            ConversationContextReducer contextReducer,
            ConversationHistoryProperties properties
    ) {
        this.conversationMemoryService = conversationMemoryService;
        this.contextReducer = contextReducer;
        this.properties = properties;
    }

    /**
     * Creates a new conversation identifier.
     *
     * @return created conversation
     */
    @PostMapping("/api/v1/conversations")
    public ConversationCreatedResponse create() {
        return new ConversationCreatedResponse(UUID.randomUUID().toString(), Instant.now());
    }

    /**
     * Lists stored messages for a conversation.
     *
     * @param conversationId conversation identifier
     * @return messages
     */
    @GetMapping("/api/v1/conversations/{conversationId}/messages")
    public List<ConversationMessage> messages(@PathVariable String conversationId) {
        return conversationMemoryService.getMessages(conversationId);
    }

    /**
     * Deletes a conversation context.
     *
     * @param conversationId conversation identifier
     * @return deletion response
     */
    @DeleteMapping("/api/v1/conversations/{conversationId}")
    public ResponseEntity<ConversationDeletedResponse> delete(@PathVariable String conversationId) {
        int deleted = conversationMemoryService.deleteConversation(conversationId);
        return ResponseEntity.ok(new ConversationDeletedResponse(conversationId, deleted));
    }

    /**
     * Builds the prompt-safe conversation context for diagnostics.
     *
     * @param conversationId conversation identifier
     * @return debug context
     */
    @GetMapping("/api/v1/debug/conversations/{conversationId}/context")
    public ConversationContextDebugResponse debugContext(@PathVariable String conversationId) {
        List<ConversationMessage> stored = conversationMemoryService.getMessages(conversationId);
        List<ConversationMessage> selected = contextReducer.reduce(stored, "");
        int characters = selected.stream().mapToInt(message -> message.content().length()).sum();
        return new ConversationContextDebugResponse(
                conversationId,
                properties.enabled(),
                properties.maxMessages(),
                properties.maxCharacters(),
                stored.size(),
                selected.size(),
                characters,
                selected
        );
    }

    /**
     * Created conversation response.
     */
    public record ConversationCreatedResponse(String conversationId, Instant createdAt) {
    }

    /**
     * Conversation deletion response.
     */
    public record ConversationDeletedResponse(String conversationId, int deletedMessages) {
    }

    /**
     * Conversation context debug response.
     */
    public record ConversationContextDebugResponse(
            String conversationId,
            boolean enabled,
            int maxMessages,
            int maxCharacters,
            int storedMessages,
            int selectedMessages,
            int selectedCharacters,
            List<ConversationMessage> messages
    ) {
    }
}
