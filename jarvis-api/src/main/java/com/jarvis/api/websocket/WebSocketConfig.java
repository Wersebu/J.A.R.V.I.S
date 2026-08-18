package com.jarvis.api.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.api.service.ChatService;
import com.jarvis.tools.mcp.McpServerManager;
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
    private final McpServerManager mcpServerManager;

    /**
     * Creates the WebSocket configuration.
     *
     * @param chatService chat service
     * @param objectMapper JSON mapper
     */
    public WebSocketConfig(
            ChatService chatService,
            ObjectMapper objectMapper,
            WebSocketWindowsMcpBridgeGateway windowsMcpBridgeGateway,
            McpServerManager mcpServerManager
    ) {
        this.chatService = chatService;
        this.objectMapper = objectMapper;
        this.windowsMcpBridgeGateway = windowsMcpBridgeGateway;
        this.mcpServerManager = mcpServerManager;
    }

    /**
     * Registers WebSocket handlers.
     *
     * @param registry handler registry
     */
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new JarvisWebSocketHandler(chatService, objectMapper, windowsMcpBridgeGateway, mcpServerManager), "/ws/jarvis")
                .setAllowedOrigins("*");
    }
}
