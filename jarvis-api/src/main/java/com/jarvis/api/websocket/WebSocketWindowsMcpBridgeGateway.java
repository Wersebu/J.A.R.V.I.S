package com.jarvis.api.websocket;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.tools.mcp.McpAccessLevel;
import com.jarvis.tools.mcp.McpCallResult;
import com.jarvis.tools.mcp.McpContentItem;
import com.jarvis.tools.mcp.McpException;
import com.jarvis.tools.mcp.McpServerProperties;
import com.jarvis.tools.mcp.McpToolDescriptor;
import com.jarvis.tools.mcp.WindowsMcpBridgeGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Core-side gateway that forwards MCP operations to the connected Windows client.
 */
@Service
public class WebSocketWindowsMcpBridgeGateway implements WindowsMcpBridgeGateway {

    private static final Logger LOGGER = LoggerFactory.getLogger(WebSocketWindowsMcpBridgeGateway.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;
    private final AtomicReference<WebSocketSession> bridgeSession = new AtomicReference<>();
    private final Map<String, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();

    /**
     * Creates the gateway.
     *
     * @param objectMapper JSON mapper
     */
    public WebSocketWindowsMcpBridgeGateway(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean isBridgeConnected() {
        WebSocketSession session = bridgeSession.get();
        return session != null && session.isOpen();
    }

    @Override
    public void initialize(String serverId, McpServerProperties properties, String clientVersion) {
        Map<String, Object> server = new LinkedHashMap<>();
        server.put("command", properties.getCommand());
        server.put("args", properties.getArgs());
        server.put("accessLevel", properties.getAccessLevel().name());
        server.put("startupTimeoutMs", properties.getStartupTimeout().toMillis());
        server.put("initializeTimeoutMs", properties.getInitializeTimeout().toMillis());
        server.put("listToolsTimeoutMs", properties.getListToolsTimeout().toMillis());
        server.put("callTimeoutMs", properties.getCallTimeout().toMillis());
        server.put("clientVersion", clientVersion);
        request("MCP_CONNECT", serverId, server, properties.getStartupTimeout().plus(properties.getInitializeTimeout()));
    }

    @Override
    public List<McpToolDescriptor> listTools(String serverId, McpServerProperties properties) {
        JsonNode payload = request("MCP_LIST_TOOLS", serverId, Map.of(), properties.getListToolsTimeout());
        List<McpToolDescriptor> descriptors = new ArrayList<>();
        JsonNode tools = payload.path("tools");
        if (!tools.isArray()) {
            return List.of();
        }
        for (JsonNode tool : tools) {
            String name = tool.path("name").asText("");
            if (name.isBlank()) {
                continue;
            }
            descriptors.add(new McpToolDescriptor(
                    serverId,
                    name,
                    tool.path("description").asText("MCP tool " + name),
                    objectMapper.convertValue(tool.path("inputSchema"), MAP_TYPE),
                    properties.getAccessLevel()
            ));
        }
        return List.copyOf(descriptors);
    }

    @Override
    public McpCallResult callTool(String serverId, String toolName, Map<String, Object> arguments, Duration timeout) {
        JsonNode payload = request("MCP_CALL_TOOL", serverId, Map.of(
                "toolName", toolName,
                "arguments", arguments == null ? Map.of() : arguments
        ), timeout);
        return toCallResult(payload);
    }

    @Override
    public void disconnect(String serverId) {
        if (!isBridgeConnected()) {
            return;
        }
        try {
            request("MCP_DISCONNECT", serverId, Map.of(), Duration.ofSeconds(3));
        } catch (RuntimeException exception) {
            LOGGER.debug("[MCP_BRIDGE] disconnect failed server={} error={}", serverId, exception.getMessage());
        }
    }

    @Override
    public String bridgeStatus() {
        return isBridgeConnected() ? "CONNECTED" : "BRIDGE_UNAVAILABLE";
    }

    /**
     * Registers a Windows WebSocket session as the active bridge.
     *
     * @param session WebSocket session
     */
    public void register(WebSocketSession session) {
        WebSocketSession previous = bridgeSession.getAndSet(session);
        if (previous != null && previous != session) {
            completeAllExceptionally("Windows MCP bridge session was replaced.");
        }
        LOGGER.info("[MCP_BRIDGE] Windows bridge registered session={}", session.getId());
    }

    /**
     * Handles a response sent by the Windows MCP bridge.
     *
     * @param message JSON message
     */
    public void handleResponse(JsonNode message) {
        String requestId = message.path("requestId").asText("");
        CompletableFuture<JsonNode> future = pending.remove(requestId);
        if (future == null) {
            LOGGER.debug("[MCP_BRIDGE] stale response requestId={}", requestId);
            return;
        }
        if (!message.path("success").asBoolean(false)) {
            String errorCode = message.path("errorCode").asText("MCP_BRIDGE_ERROR");
            String errorMessage = message.path("errorMessage").asText("Windows MCP bridge request failed.");
            future.completeExceptionally(new McpException(errorCode + ": " + errorMessage));
            return;
        }
        future.complete(message.path("payload"));
    }

    /**
     * Detaches a closed bridge session and fails pending requests.
     *
     * @param session closed session
     */
    public void detach(WebSocketSession session) {
        if (bridgeSession.compareAndSet(session, null)) {
            completeAllExceptionally("Windows MCP bridge disconnected.");
            LOGGER.info("[MCP_BRIDGE] Windows bridge disconnected session={}", session.getId());
        }
    }

    private JsonNode request(String type, String serverId, Map<String, Object> payload, Duration timeout) {
        WebSocketSession session = bridgeSession.get();
        if (session == null || !session.isOpen()) {
            throw new McpException("Windows MCP bridge is not connected.");
        }
        String requestId = UUID.randomUUID().toString();
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(requestId, future);
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", type);
        message.put("requestId", requestId);
        message.put("serverId", serverId);
        message.put("payload", payload == null ? Map.of() : payload);
        try {
            send(session, message);
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            pending.remove(requestId);
            throw new McpException("Windows MCP bridge request timed out: " + type + " server=" + serverId, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            pending.remove(requestId);
            throw new McpException("Interrupted while waiting for Windows MCP bridge.", exception);
        } catch (Exception exception) {
            pending.remove(requestId);
            if (exception instanceof McpException mcpException) {
                throw mcpException;
            }
            throw new McpException("Windows MCP bridge request failed: " + type + " server=" + serverId, exception);
        }
    }

    private void send(WebSocketSession session, Object message) {
        synchronized (session) {
            try {
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(message)));
            } catch (JsonProcessingException exception) {
                throw new McpException("Failed to serialize Windows MCP bridge request.", exception);
            } catch (IOException exception) {
                throw new McpException("Failed to send Windows MCP bridge request.", exception);
            }
        }
    }

    private McpCallResult toCallResult(JsonNode payload) {
        List<McpContentItem> content = new ArrayList<>();
        JsonNode contentNode = payload.path("content");
        if (contentNode.isArray()) {
            for (JsonNode item : contentNode) {
                content.add(new McpContentItem(
                        item.path("type").asText("unknown"),
                        item.path("text").isMissingNode() ? null : item.path("text").asText(),
                        item.path("mimeType").isMissingNode() ? null : item.path("mimeType").asText(),
                        item.path("data").isMissingNode() ? null : item.path("data").asText(),
                        objectMapper.convertValue(item.path("annotations"), MAP_TYPE)
                ));
            }
        }
        boolean success = payload.path("success").asBoolean(true);
        return new McpCallResult(
                success,
                content,
                objectMapper.convertValue(payload.path("structuredContent"), MAP_TYPE),
                payload.path("errorCode").asText(""),
                payload.path("errorMessage").asText("")
        );
    }

    private void completeAllExceptionally(String reason) {
        McpException exception = new McpException(reason);
        pending.forEach((requestId, future) -> future.completeExceptionally(exception));
        pending.clear();
    }
}
