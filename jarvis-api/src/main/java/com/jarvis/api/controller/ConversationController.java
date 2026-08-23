package com.jarvis.api.controller;

import com.jarvis.common.memory.ConversationMessage;
import com.jarvis.memory.ConversationMemoryService;
import com.jarvis.memory.conversation.ConversationContextReducer;
import com.jarvis.memory.conversation.ConversationHistoryProperties;
import com.jarvis.memory.conversation.ConversationMessageRepository;
import com.jarvis.memory.conversation.ConversationRecord;
import com.jarvis.memory.conversation.ConversationRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * REST controller for persistent conversations: creation, listing, full durable history, renaming,
 * archiving, and deletion. Backed by {@link ConversationRepository} (metadata) and {@link
 * ConversationMessageRepository} (full, never-trimmed message log) - both durable stores a
 * conversation's messages are also dual-written into by {@link ConversationMemoryService}
 * regardless of how the bounded working-memory window used for prompt building trims them.
 */
@RestController
public class ConversationController {

    private final ConversationMemoryService conversationMemoryService;
    private final ConversationContextReducer contextReducer;
    private final ConversationHistoryProperties properties;
    private final ConversationRepository conversationRepository;
    private final ConversationMessageRepository conversationMessageRepository;

    /**
     * Creates the conversation controller.
     *
     * @param conversationMemoryService conversation memory service
     * @param contextReducer conversation context reducer
     * @param properties conversation history properties
     * @param conversationRepository durable conversation metadata store
     * @param conversationMessageRepository durable, never-trimmed conversation message store
     */
    public ConversationController(
            ConversationMemoryService conversationMemoryService,
            ConversationContextReducer contextReducer,
            ConversationHistoryProperties properties,
            ConversationRepository conversationRepository,
            ConversationMessageRepository conversationMessageRepository
    ) {
        this.conversationMemoryService = conversationMemoryService;
        this.contextReducer = contextReducer;
        this.properties = properties;
        this.conversationRepository = conversationRepository;
        this.conversationMessageRepository = conversationMessageRepository;
    }

    /**
     * Creates a new, durably persisted conversation.
     *
     * @return the created conversation
     */
    @PostMapping("/api/v1/conversations")
    public ConversationResponse create() {
        Instant now = Instant.now();
        ConversationRecord record = conversationRepository.createIfAbsent(UUID.randomUUID().toString(), now);
        return ConversationResponse.from(record);
    }

    /**
     * Lists every conversation, most recently active first.
     *
     * @return conversation summaries
     */
    @GetMapping("/api/v1/conversations")
    public List<ConversationResponse> list() {
        return conversationRepository.list().stream().map(ConversationResponse::from).toList();
    }

    /**
     * Fetches one conversation's metadata.
     *
     * @param conversationId conversation identifier
     * @return the conversation, or {@code 404} when unknown
     */
    @GetMapping("/api/v1/conversations/{conversationId}")
    public ResponseEntity<ConversationResponse> get(@PathVariable String conversationId) {
        return conversationRepository.find(conversationId)
                .map(ConversationResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Lists a conversation's full, durable message history (never the bounded working-memory
     * window used for prompt building - the Windows UI needs the real, complete history).
     *
     * @param conversationId conversation identifier
     * @return every stored message, oldest first
     */
    @GetMapping("/api/v1/conversations/{conversationId}/messages")
    public List<ConversationMessage> messages(@PathVariable String conversationId) {
        return conversationMessageRepository.getAllMessages(conversationId);
    }

    /**
     * Renames and/or archives a conversation - a partial update, only the provided fields change.
     * Never a destructive action; archiving is always reversible via the same endpoint.
     *
     * @param conversationId conversation identifier
     * @param request fields to update
     * @return the updated conversation, or {@code 404} when unknown
     */
    @PatchMapping("/api/v1/conversations/{conversationId}")
    public ResponseEntity<ConversationResponse> patch(
            @PathVariable String conversationId,
            @RequestBody ConversationPatchRequest request
    ) {
        if (conversationRepository.find(conversationId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        if (request.title() != null && !request.title().isBlank()) {
            conversationRepository.rename(conversationId, request.title());
        }
        if (request.archived() != null) {
            conversationRepository.setArchived(conversationId, request.archived());
        }
        if (request.folderId() != null) {
            conversationRepository.moveToFolder(conversationId, request.folderId());
        }
        return conversationRepository.find(conversationId)
                .map(ConversationResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Deletes a conversation and all of its messages (working memory and durable history alike) -
     * an explicit, irreversible action; callers must confirm with the user before calling this.
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
     * Builds the prompt-safe conversation context for diagnostics - the bounded, reduced window
     * actually sent to the model, distinct from the full history in {@link #messages}.
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
     * Conversation summary response.
     */
    public record ConversationResponse(
            String id,
            String folderId,
            String title,
            Instant createdAt,
            Instant updatedAt,
            boolean archived,
            String lastModel,
            String titleSource
    ) {
        public ConversationResponse(
                String id,
                String title,
                Instant createdAt,
                Instant updatedAt,
                boolean archived,
                String lastModel,
                String titleSource
        ) {
            this(id, "", title, createdAt, updatedAt, archived, lastModel, titleSource);
        }

        private static ConversationResponse from(ConversationRecord record) {
            return new ConversationResponse(record.id(), record.folderId(), record.title(), record.createdAt(),
                    record.updatedAt(), record.archived(), record.lastModel(), record.titleSource());
        }
    }

    /**
     * Partial-update request for {@link #patch}.
     *
     * @param title new title, ignored when null/blank
     * @param archived new archived state, ignored when null
     */
    public record ConversationPatchRequest(String title, Boolean archived, String folderId) {
        public ConversationPatchRequest(String title, Boolean archived) {
            this(title, archived, null);
        }
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
