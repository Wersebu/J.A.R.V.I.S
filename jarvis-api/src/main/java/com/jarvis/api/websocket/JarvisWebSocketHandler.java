package com.jarvis.api.websocket;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.api.diagnostics.JarvisLogBroadcaster;
import com.jarvis.api.service.ChatService;
import com.jarvis.common.auth.CurrentUserContext;
import com.jarvis.common.dto.ChatRequest;
import com.jarvis.common.event.CognitiveEvent;
import com.jarvis.tools.mcp.McpServerManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Persistent WebSocket endpoint for realtime Jarvis communication.
 */
public class JarvisWebSocketHandler extends TextWebSocketHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(JarvisWebSocketHandler.class);
    private static final String LOG_SUBSCRIPTION_ATTRIBUTE = "jarvisLogSubscription";
    private static final String COGNITIVE_EVENT_SUBSCRIPTION_ATTRIBUTE = "jarvisCognitiveEventSubscription";

    private final ChatService chatService;
    private final ObjectMapper objectMapper;
    private final WebSocketWindowsMcpBridgeGateway windowsMcpBridgeGateway;
    private final McpServerManager mcpServerManager;
    /**
     * Jakarta/Spring WebSocket containers deliver one session's incoming frames strictly
     * sequentially - the container will not invoke {@link #handleTextMessage} again for this
     * session until the current invocation returns. A chat request used to run {@code
     * chatService.stream(...)} synchronously inside that callback, so a single long-running chat
     * pipeline (easily minutes, e.g. a multi-turn native tool loop) blocked delivery of every other
     * incoming frame on the SAME session for its entire duration - including an {@code
     * MCP_BRIDGE_RESPONSE} the very same pipeline was waiting on, since chat and the MCP bridge
     * share this one persistent connection. A real production trace confirmed this exactly: the
     * Windows bridge answered an MCP tool call in 0-4ms, but Core did not see that response until
     * the chat pipeline finally finished - 2-4 minutes later - because the response frame sat queued
     * behind the still-running {@code handleTextMessage} call for the chat request itself. Chat
     * processing now runs on this dedicated executor instead, so {@code handleTextMessage} returns
     * immediately and the container is free to deliver bridge frames (and any other session traffic)
     * while a chat pipeline is still running.
     */
    private final ExecutorService chatExecutor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "jarvis-ws-chat");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * Creates the WebSocket handler.
     *
     * @param chatService chat service
     * @param objectMapper JSON mapper
     */
    public JarvisWebSocketHandler(
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
     * Confirms that the realtime WebSocket endpoint is available.
     *
     * @param session active WebSocket session
     * @throws Exception when the response cannot be sent
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        AutoCloseable logSubscription = JarvisLogBroadcaster.subscribe(event -> send(session, event));
        session.getAttributes().put(LOG_SUBSCRIPTION_ATTRIBUTE, logSubscription);
        AutoCloseable cognitiveEventSubscription = CognitiveEventBroadcaster.subscribe(event -> send(session, event));
        session.getAttributes().put(COGNITIVE_EVENT_SUBSCRIPTION_ATTRIBUTE, cognitiveEventSubscription);
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
        JsonNode root;
        try {
            root = objectMapper.readTree(message.getPayload());
        } catch (JsonProcessingException exception) {
            send(session, new WebSocketStatus("ERROR", "Invalid request JSON"));
            return;
        }
        String messageType = root.path("type").asText("");
        if ("MCP_BRIDGE_RESPONSE".equals(messageType)) {
            LOGGER.info("[MCP_BRIDGE] inbound response session={} requestId={} server={} jsonBytes={}",
                    session.getId(),
                    root.path("requestId").asText(""),
                    root.path("serverId").asText(""),
                    message.getPayload().getBytes(StandardCharsets.UTF_8).length);
        }
        if (handleBridgeMessage(session, root, messageType)) {
            return;
        }

        ChatRequest request;
        try {
            request = objectMapper.treeToValue(root, ChatRequest.class);
        } catch (JsonProcessingException exception) {
            send(session, new WebSocketStatus("ERROR", "Invalid chat request JSON"));
            return;
        }

        // Dispatched, not run inline - see chatExecutor's javadoc. handleTextMessage must return
        // quickly regardless of how long the chat pipeline takes, so the container keeps delivering
        // other frames on this same session (MCP bridge responses in particular) throughout.
        chatExecutor.submit(() -> {
            try {
                CurrentUserContext.runAs(String.valueOf(session.getAttributes().getOrDefault("jarvis.userId", CurrentUserContext.LOCAL_USER_ID)),
                        () -> chatService.stream(request, event -> sendEvent(session, event)));
                send(session, new WebSocketStatus("COMPLETED", "Request completed"));
            } catch (RuntimeException exception) {
                LOGGER.error("[JARVIS] WebSocket chat failed", exception);
                send(session, new WebSocketStatus("ERROR", exception.getMessage() == null ? "Request failed" : exception.getMessage()));
            }
        });
    }

    /**
     * Handles WebSocket closure.
     *
     * @param session active WebSocket session
     * @param status close status
     */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        if (windowsMcpBridgeGateway.detach(session)) {
            mcpServerManager.handleWindowsBridgeDisconnected();
            send(session, new WebSocketStatus("MCP_STATUS_CHANGED", "Windows MCP bridge disconnected"));
        }
        closeSubscription(session, LOG_SUBSCRIPTION_ATTRIBUTE);
        closeSubscription(session, COGNITIVE_EVENT_SUBSCRIPTION_ATTRIBUTE);
    }

    /**
     * Cleans up session resources after transport failure.
     *
     * @param session active WebSocket session
     * @param exception transport exception
     */
    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        if (windowsMcpBridgeGateway.detach(session)) {
            mcpServerManager.handleWindowsBridgeDisconnected();
            send(session, new WebSocketStatus("MCP_STATUS_CHANGED", "Windows MCP bridge disconnected"));
        }
        closeSubscription(session, LOG_SUBSCRIPTION_ATTRIBUTE);
        closeSubscription(session, COGNITIVE_EVENT_SUBSCRIPTION_ATTRIBUTE);
    }

    private boolean handleBridgeMessage(WebSocketSession session, JsonNode root, String messageType) {
        if ("MCP_BRIDGE_REGISTER".equals(messageType)) {
            windowsMcpBridgeGateway.register(session);
            send(session, new WebSocketStatus("MCP_BRIDGE_CONNECTED", "Windows MCP bridge connected"));
            CompletableFuture.runAsync(() -> activateWindowsBridgeServers(session));
            return true;
        }
        if ("MCP_BRIDGE_RESPONSE".equals(messageType)) {
            windowsMcpBridgeGateway.handleResponse(root);
            return true;
        }
        return false;
    }

    private void activateWindowsBridgeServers(WebSocketSession session) {
        try {
            mcpServerManager.activateWindowsBridgeServers();
            send(session, new WebSocketStatus("MCP_STATUS_CHANGED", "Windows MCP status changed"));
        } catch (RuntimeException exception) {
            LOGGER.warn("[MCP_BRIDGE] Windows MCP activation failed: {}", exception.getMessage());
            send(session, new WebSocketStatus("MCP_STATUS_CHANGED", "Windows MCP activation failed: " + exception.getMessage()));
        }
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

    private void closeSubscription(WebSocketSession session, String attributeName) {
        Object subscription = session.getAttributes().remove(attributeName);
        if (subscription instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception exception) {
                LOGGER.debug("[JARVIS] Could not close subscription {}: {}", attributeName, exception.getMessage());
            }
        }
    }

    private record WebSocketStatus(String type, String message) {
    }
}
