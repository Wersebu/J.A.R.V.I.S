package com.jarvis.api.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.api.service.ChatService;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Configures the initial Jarvis WebSocket endpoint.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final ChatService chatService;
    private final ObjectMapper objectMapper;
    private final WebSocketWindowsMcpBridgeGateway windowsMcpBridgeGateway;

    /**
     * Creates the WebSocket configuration.
     *
     * @param chatService chat service
     * @param objectMapper JSON mapper
     */
    public WebSocketConfig(
            ChatService chatService,
            ObjectMapper objectMapper,
            WebSocketWindowsMcpBridgeGateway windowsMcpBridgeGateway
    ) {
        this.chatService = chatService;
        this.objectMapper = objectMapper;
        this.windowsMcpBridgeGateway = windowsMcpBridgeGateway;
    }

    /**
     * Registers WebSocket handlers.
     *
     * @param registry handler registry
     */
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new JarvisWebSocketHandler(chatService, objectMapper, windowsMcpBridgeGateway), "/ws/jarvis")
                .setAllowedOrigins("*");
    }
}
