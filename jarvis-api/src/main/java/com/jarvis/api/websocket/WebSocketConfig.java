package com.jarvis.api.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.api.service.ChatService;
import com.jarvis.tools.mcp.McpServerManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

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
    private final int maxTextMessageSize;
    private final int maxBinaryMessageSize;

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
            McpServerManager mcpServerManager,
            @Value("${jarvis.websocket.max-text-message-size:4194304}") int maxTextMessageSize,
            @Value("${jarvis.websocket.max-binary-message-size:8388608}") int maxBinaryMessageSize
    ) {
        this.chatService = chatService;
        this.objectMapper = objectMapper;
        this.windowsMcpBridgeGateway = windowsMcpBridgeGateway;
        this.mcpServerManager = mcpServerManager;
        this.maxTextMessageSize = maxTextMessageSize;
        this.maxBinaryMessageSize = maxBinaryMessageSize;
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

    /**
     * Configures the embedded servlet WebSocket container frame buffers.
     *
     * @return WebSocket servlet container factory
     */
    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public ServletServerContainerFactoryBean webSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(maxTextMessageSize);
        container.setMaxBinaryMessageBufferSize(maxBinaryMessageSize);
        return container;
    }
}
