package com.jarvis.api.websocket;

import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * Placeholder WebSocket endpoint for future realtime Jarvis communication.
 */
public class JarvisWebSocketHandler extends TextWebSocketHandler {

    /**
     * Confirms that the headless WebSocket endpoint is available.
     *
     * @param session active WebSocket session
     * @throws Exception when the response cannot be sent
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        session.sendMessage(new TextMessage("Jarvis WebSocket online"));
    }

    /**
     * Keeps the v0.1 WebSocket endpoint intentionally logic-free.
     *
     * @param session active WebSocket session
     * @param status close status
     */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
    }
}
