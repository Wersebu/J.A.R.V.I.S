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
import java.util.Optional;
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
    private final PendingChatStreamStore pendingChatStreamStore;

    /**
     * Creates a chat controller.
     *
     * @param chatService chat orchestration service
     * @param pendingChatStreamStore short-lived POST-to-GET handoff store for SSE requests
     */
    public ChatController(ChatService chatService, PendingChatStreamStore pendingChatStreamStore) {
        this.chatService = chatService;
        this.pendingChatStreamStore = pendingChatStreamStore;
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
     * Submits a chat request body ahead of opening the SSE stream.
     *
     * <p>Server-Sent Events is GET-only, so the message text (and attachments) can never be part
     * of that GET call - putting them in a URL query string risks exceeding the servlet
     * container's max request-line/header size for anything longer than a short message. This
     * endpoint holds the request just long enough for the immediately following
     * {@link #stream(String)} GET call to retrieve it by token.</p>
     *
     * @param request chat request
     * @return correlation token to pass to {@link #stream(String)}
     */
    @PostMapping(path = "/stream", consumes = MediaType.APPLICATION_JSON_VALUE)
    public StreamToken prepareStream(@RequestBody ChatRequest request) {
        return new StreamToken(pendingChatStreamStore.store(request));
    }

    /**
     * Streams chat lifecycle events and generated tokens using Server-Sent Events, for a request
     * previously submitted via {@link #prepareStream(ChatRequest)}.
     *
     * @param token correlation token returned by {@link #prepareStream(ChatRequest)}
     * @return SSE emitter
     */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam String token) {
        SseStreamSession session = new SseStreamSession(new SseEmitter(SSE_TIMEOUT_MS));
        Optional<ChatRequest> request = pendingChatStreamStore.consume(token);
        if (request.isEmpty()) {
            session.completeWithError(new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown or expired stream token"));
            return session.emitter();
        }

        CompletableFuture.runAsync(() -> {
            try {
                chatService.stream(request.get(), session::send);
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
