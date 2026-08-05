package com.jarvis.api.controller;

import com.jarvis.api.service.ChatService;
import com.jarvis.common.dto.ChatRequest;
import com.jarvis.common.dto.ChatResponse;
import com.jarvis.common.event.CognitiveEvent;
import com.jarvis.common.knowledge.KnowledgeMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * REST endpoint for plain text chat interactions.
 */
@RestController
@RequestMapping(path = "/api/v1/chat", produces = MediaType.APPLICATION_JSON_VALUE)
public class ChatController {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChatController.class);
    private static final long SSE_TIMEOUT_MS = 600_000L;

    private final ChatService chatService;

    /**
     * Creates a chat controller.
     *
     * @param chatService chat orchestration service
     */
    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    /**
     * Sends a user message to Jarvis and returns the generated response.
     *
     * @param request chat request
     * @return chat response
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ChatResponse chat(@RequestBody ChatRequest request) {
        return chatService.chat(request);
    }

    /**
     * Streams chat lifecycle events and generated tokens using Server-Sent Events.
     *
     * @param conversationId stable conversation identifier
     * @param message user message text
     * @return SSE emitter
     */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @RequestParam String conversationId,
            @RequestParam String message,
            @RequestParam(defaultValue = "AUTO") KnowledgeMode knowledgeMode
    ) {
        SseStreamSession session = new SseStreamSession(new SseEmitter(SSE_TIMEOUT_MS));
        ChatRequest request = new ChatRequest(conversationId, message, null, knowledgeMode);

        CompletableFuture.runAsync(() -> {
            try {
                chatService.stream(request, session::send);
                session.complete();
            } catch (SseDeliveryException exception) {
                LOGGER.warn("[JARVIS] SSE client disconnected: {}", exception.getMessage());
                session.complete();
            } catch (RuntimeException exception) {
                LOGGER.error("[JARVIS] SSE stream failed", exception);
                session.completeWithError(exception);
            }
        });

        return session.emitter();
    }

    /**
     * Runtime exception used when SSE delivery fails.
     */
    private static final class SseDeliveryException extends ResponseStatusException {

        /**
         * Creates an SSE delivery exception.
         *
         * @param reason failure reason
         * @param cause underlying cause
         */
        private SseDeliveryException(String reason, Throwable cause) {
            super(HttpStatus.INTERNAL_SERVER_ERROR, reason, cause);
        }
    }

    /**
     * State-aware wrapper around one SSE response.
     */
    private static final class SseStreamSession {

        private final SseEmitter emitter;
        private final AtomicBoolean open = new AtomicBoolean(true);

        private SseStreamSession(SseEmitter emitter) {
            this.emitter = emitter;
            this.emitter.onCompletion(() -> open.set(false));
            this.emitter.onTimeout(() -> open.set(false));
            this.emitter.onError(error -> open.set(false));
        }

        private SseEmitter emitter() {
            return emitter;
        }

        private void send(CognitiveEvent event) {
            if (!open.get()) {
                return;
            }
            try {
                emitter.send(SseEmitter.event()
                        .name(event.event().name())
                        .data(event));
            } catch (IllegalStateException exception) {
                open.set(false);
            } catch (IOException exception) {
                open.set(false);
                throw new SseDeliveryException("Failed to send SSE event", exception);
            }
        }

        private void complete() {
            if (open.compareAndSet(true, false)) {
                emitter.complete();
            }
        }

        private void completeWithError(RuntimeException exception) {
            if (open.compareAndSet(true, false)) {
                emitter.completeWithError(exception);
            }
        }
    }
}
