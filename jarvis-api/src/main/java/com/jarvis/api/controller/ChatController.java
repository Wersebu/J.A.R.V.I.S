package com.jarvis.api.controller;

import com.jarvis.api.service.ChatService;
import com.jarvis.common.dto.ChatRequest;
import com.jarvis.common.dto.ChatResponse;
import com.jarvis.common.event.CognitiveEvent;
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
            @RequestParam String message
    ) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        ChatRequest request = new ChatRequest(conversationId, message);

        CompletableFuture.runAsync(() -> {
            try {
                chatService.stream(request, event -> send(emitter, event));
                emitter.complete();
            } catch (SseDeliveryException exception) {
                LOGGER.warn("[JARVIS] SSE client disconnected: {}", exception.getMessage());
                emitter.complete();
            } catch (RuntimeException exception) {
                LOGGER.error("[JARVIS] SSE stream failed", exception);
                emitter.completeWithError(exception);
            }
        });

        return emitter;
    }

    private void send(SseEmitter emitter, CognitiveEvent event) {
        try {
            emitter.send(SseEmitter.event()
                    .name(event.event().name())
                    .data(event));
        } catch (IOException exception) {
            throw new SseDeliveryException("Failed to send SSE event", exception);
        }
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
}
