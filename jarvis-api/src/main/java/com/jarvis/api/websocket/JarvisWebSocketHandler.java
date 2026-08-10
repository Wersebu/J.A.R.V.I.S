package com.jarvis.api.websocket;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.api.diagnostics.JarvisLogBroadcaster;
import com.jarvis.api.service.ChatService;
import com.jarvis.common.dto.ChatRequest;
import com.jarvis.common.event.CognitiveEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;

/**
 * Persistent WebSocket endpoint for realtime Jarvis communication.
 */
public class JarvisWebSocketHandler extends TextWebSocketHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(JarvisWebSocketHandler.class);
    private static final String LOG_SUBSCRIPTION_ATTRIBUTE = "jarvisLogSubscription";

    private final ChatService chatService;
    private final ObjectMapper objectMapper;

    /**
     * Creates the WebSocket handler.
     *
     * @param chatService chat service
     * @param objectMapper JSON mapper
     */
    public JarvisWebSocketHandler(ChatService chatService, ObjectMapper objectMapper) {
        this.chatService = chatService;
        this.objectMapper = objectMapper;
    }

    /**
     * Confirms that the realtime WebSocket endpoint is available.
     *
     * @param session active WebSocket session
     * @throws Exception when the response cannot be sent
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        AutoCloseable logSubscription = JarvisLogBroadcaster.subscribe(event -> send(session, event));
        session.getAttributes().put(LOG_SUBSCRIPTION_ATTRIBUTE, logSubscription);
        send(session, new WebSocketStatus("CONNECTED", "Jarvis WebSocket online"));
    }

    /**
     * Processes a chat message through the active cognitive pipeline.
     *
     * @param session active WebSocket session
     * @param message incoming text message
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        ChatRequest request;
        try {
            request = objectMapper.readValue(message.getPayload(), ChatRequest.class);
        } catch (JsonProcessingException exception) {
            send(session, new WebSocketStatus("ERROR", "Invalid chat request JSON"));
            return;
        }

        try {
            chatService.stream(request, event -> sendEvent(session, event));
            send(session, new WebSocketStatus("COMPLETED", "Request completed"));
        } catch (RuntimeException exception) {
            LOGGER.error("[JARVIS] WebSocket chat failed", exception);
            send(session, new WebSocketStatus("ERROR", exception.getMessage() == null ? "Request failed" : exception.getMessage()));
        }
    }

    /**
     * Handles WebSocket closure.
     *
     * @param session active WebSocket session
     * @param status close status
     */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        closeLogSubscription(session);
    }

    /**
     * Cleans up session resources after transport failure.
     *
     * @param session active WebSocket session
     * @param exception transport exception
     */
    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        closeLogSubscription(session);
    }

    private void sendEvent(WebSocketSession session, CognitiveEvent event) {
        send(session, event);
    }

    private void send(WebSocketSession session, Object payload) {
        if (!session.isOpen()) {
            return;
        }
        synchronized (session) {
            try {
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
            } catch (IllegalStateException exception) {
                LOGGER.debug("[JARVIS] WebSocket session already closed");
            } catch (IOException exception) {
                LOGGER.debug("[JARVIS] Could not send WebSocket message: {}", exception.getMessage());
            }
        }
    }

    private void closeLogSubscription(WebSocketSession session) {
        Object subscription = session.getAttributes().remove(LOG_SUBSCRIPTION_ATTRIBUTE);
        if (subscription instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception exception) {
                LOGGER.debug("[JARVIS] Could not close log subscription: {}", exception.getMessage());
            }
        }
    }

    private record WebSocketStatus(String type, String message) {
    }
}
