package com.jarvis.api.websocket;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.api.service.WindowsCodingBridgeGateway;
import com.jarvis.common.auth.CurrentUserContext;
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
 *
 * <p>Every user has their own bridge: a Windows app registers under the account it logged in with, and a
 * request goes only to the PC of the user the calling thread runs as ({@link CurrentUserContext}, set for
 * every chat and coding task). One person's conversation can never reach another person's files, shell
 * or screen, and a response from one PC can never complete a request sent to another.</p>
 */
@Service
public class WebSocketWindowsMcpBridgeGateway implements WindowsMcpBridgeGateway, WindowsCodingBridgeGateway {

    private static final Logger LOGGER = LoggerFactory.getLogger(WebSocketWindowsMcpBridgeGateway.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    /**
     * Every timeout Core hands to the Windows bridge (via {@code initialize}'s
     * {@code startupTimeoutMs}/{@code initializeTimeoutMs}/{@code listToolsTimeoutMs}/{@code callTimeoutMs})
     * is also the exact duration {@link WindowsMcpBridgeGateway} itself waits for on this side - the
     * Windows client's own internal watchdog (see {@code WindowsMcpProcessClient}) starts its clock
     * strictly after receiving and dispatching the request, so without extra slack here Core's own
     * {@code future.get} always times out first (or ties). That discards the pending future
     * (see {@link #handleResponse}) moments before the Windows side's own more specific, more
     * informative failure - stderr excerpts, the exact JSON-RPC error, etc. - would have arrived, so
     * it is logged and dropped as a "stale response" instead of ever reaching the model. Waiting this
     * much longer than what was told to Windows lets the Windows-side timeout always win the race.
     */
    private static final Duration BRIDGE_RESPONSE_SLACK = Duration.ofSeconds(5);

    private final ObjectMapper objectMapper;
    private final Map<String, Bridge> bridges = new ConcurrentHashMap<>();
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    /**
     * How long in-flight bridge requests survive a dropped Windows socket. The same Windows app
     * instance usually reconnects within seconds and then delivers the result of a long command on
     * the new socket, so failing everything instantly would throw away e.g. a 10-minute build.
     */
    private static final Duration RECONNECT_GRACE = Duration.ofSeconds(90);
    private static final java.util.concurrent.ScheduledExecutorService GRACE_SCHEDULER =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "jarvis-mcp-bridge-grace");
                thread.setDaemon(true);
                return thread;
            });

    /** One user's Windows app: its current socket, every open socket and the reconnect state. */
    private static final class Bridge {
        private final AtomicReference<WebSocketSession> session = new AtomicReference<>();
        private final java.util.Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();
        private volatile String instanceId = "";
        private volatile java.util.concurrent.ScheduledFuture<?> graceFailure;
    }

    private record Pending(String owner, CompletableFuture<JsonNode> future) {
    }

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
        WebSocketSession session = bridge(caller()).session.get();
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
        server.put("env", properties.getEnv());
        request("MCP_CONNECT", serverId, server, properties.getStartupTimeout().plus(properties.getInitializeTimeout()));
    }

    @Override
    public List<McpToolDescriptor> listTools(String serverId, McpServerProperties properties) {
        JsonNode payload = request("MCP_LIST_TOOLS", serverId, Map.of(), properties.getListToolsTimeout());
        JsonNode tools = payload.path("tools");
        if (!tools.isArray()) {
            // A missing/wrong-shaped "tools" field is a protocol error, never silently treated the
            // same as a genuinely empty discovery ("connected, 0 tools") - those two outcomes used
            // to be indistinguishable, which is exactly what let a malformed Windows-side response
            // masquerade as a normal empty result instead of surfacing as the bug it actually is.
            String shape = describeShape(tools);
            LOGGER.error("[MCP_BRIDGE] listTools protocol error server={} reason=TOOLS_FIELD_NOT_ARRAY shape={}", serverId, shape);
            throw new McpException("Windows MCP bridge returned a malformed tools/list response for server '"
                    + serverId + "' - expected payload.tools to be an array, got " + shape + ".");
        }
        List<McpToolDescriptor> descriptors = new ArrayList<>();
        int skippedNameless = 0;
        for (JsonNode tool : tools) {
            String name = tool.path("name").asText("");
            if (name.isBlank()) {
                skippedNameless++;
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
        if (skippedNameless > 0) {
            LOGGER.warn("[MCP_BRIDGE] listTools skipped nameless tool descriptors server={} skipped={}", serverId, skippedNameless);
        }
        LOGGER.info("[MCP_BRIDGE] listTools server={} toolCount={} toolNames={}",
                serverId, descriptors.size(), toolNamesSummary(descriptors));
        return List.copyOf(descriptors);
    }

    private static final int MAX_LOGGED_TOOL_NAMES = 50;

    private String toolNamesSummary(List<McpToolDescriptor> descriptors) {
        List<String> names = descriptors.stream().map(McpToolDescriptor::name).limit(MAX_LOGGED_TOOL_NAMES).toList();
        String joined = String.join(",", names);
        return descriptors.size() > MAX_LOGGED_TOOL_NAMES ? joined + ",...(" + descriptors.size() + " total)" : joined;
    }

    private String describeShape(JsonNode node) {
        if (node == null || node.isMissingNode()) {
            return "MISSING_FIELD";
        }
        if (node.isNull()) {
            return "NULL";
        }
        return node.getNodeType().name();
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

    @Override
    public String codingStatus() {
        long openSessions = openBridgeSessions(bridge(caller()));
        if (openSessions == 0) {
            return "WINDOWS_BRIDGE_UNAVAILABLE";
        }
        if (openSessions > 1) {
            return "MULTIPLE_WINDOWS_BRIDGE_SESSIONS";
        }
        return "CONNECTED";
    }

    @Override
    public Map<String, Object> codingRequest(String operation, Map<String, Object> payload, Duration timeout) {
        long openSessions = openBridgeSessions(bridge(caller()));
        if (openSessions == 0) {
            throw new McpException("Windows Coding Executor is unavailable: no Windows Bridge session is connected.");
        }
        if (openSessions > 1) {
            throw new McpException("Windows Coding Executor is unavailable: multiple Windows Bridge sessions are connected.");
        }
        JsonNode response = request("CODING_EXECUTOR_REQUEST", "coding", Map.of(
                "operation", operation,
                "arguments", payload == null ? Map.of() : payload
        ), timeout);
        return objectMapper.convertValue(response, MAP_TYPE);
    }

    /**
     * Registers a Windows WebSocket session as the active bridge.
     *
     * @param session WebSocket session
     */
    public void register(WebSocketSession session) {
        register(session, "");
    }

    /**
     * Registers a Windows WebSocket session as the active bridge. When the same Windows app
     * instance (same {@code instanceId}) reconnects, in-flight requests are kept: the client answers
     * them on the new socket.
     *
     * @param session WebSocket session
     * @param instanceId id of the Windows app instance, blank when unknown
     */
    public synchronized void register(WebSocketSession session, String instanceId) {
        String owner = owner(session);
        Bridge bridge = bridges.computeIfAbsent(owner, ignored -> new Bridge());
        String newInstance = instanceId == null ? "" : instanceId;
        boolean sameInstance = !newInstance.isBlank() && newInstance.equals(bridge.instanceId);
        cancelGraceFailure(bridge);
        bridge.sessions.add(session);
        WebSocketSession previous = bridge.session.getAndSet(session);
        if (previous != null && previous != session && sameInstance) {
            bridge.sessions.remove(previous);
        }
        if (!sameInstance && (previous != null && previous != session || !bridge.instanceId.isBlank())) {
            completeAllExceptionally(owner, "Windows MCP bridge session was replaced.");
        }
        bridge.instanceId = newInstance;
        LOGGER.info("[MCP_BRIDGE] Windows bridge registered session={} user={} sameInstance={} keptPending={}",
                session.getId(), owner, sameInstance, sameInstance ? pendingCount(owner) : 0);
    }

    /**
     * Handles a response sent by the Windows MCP bridge.
     *
     * @param message JSON message
     */
    public void handleResponse(JsonNode message) {
        handleResponse(null, message);
    }

    /**
     * Handles a response sent by a Windows MCP bridge. It is accepted only from a PC of the user the
     * request was sent for.
     *
     * @param session socket the response arrived on, null when the caller already verified it
     * @param message JSON message
     */
    public void handleResponse(WebSocketSession session, JsonNode message) {
        String requestId = message.path("requestId").asText("");
        Pending entry = pending.get(requestId);
        if (entry == null) {
            LOGGER.debug("[MCP_BRIDGE] stale response requestId={}", requestId);
            return;
        }
        if (session != null && !entry.owner().equals(owner(session))) {
            LOGGER.warn("[MCP_BRIDGE] ignored response from another user's bridge requestId={} session={}", requestId, session.getId());
            return;
        }
        if (!pending.remove(requestId, entry)) {
            return;
        }
        CompletableFuture<JsonNode> future = entry.future();
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
    public synchronized boolean detach(WebSocketSession session) {
        String owner = owner(session);
        Bridge bridge = bridges.get(owner);
        if (bridge == null) {
            return false;
        }
        bridge.sessions.remove(session);
        if (bridge.session.compareAndSet(session, null)) {
            if (pendingCount(owner) == 0 || bridge.instanceId.isBlank()) {
                completeAllExceptionally(owner, "Windows MCP bridge disconnected.");
            } else {
                cancelGraceFailure(bridge);
                bridge.graceFailure = GRACE_SCHEDULER.schedule(() -> {
                    if (bridge.session.get() == null) {
                        completeAllExceptionally(owner, "Windows MCP bridge disconnected and did not reconnect in time.");
                    }
                }, RECONNECT_GRACE.toMillis(), TimeUnit.MILLISECONDS);
            }
            LOGGER.info("[MCP_BRIDGE] Windows bridge disconnected session={} user={} pendingKeptForGrace={}",
                    session.getId(), owner, pendingCount(owner));
            return true;
        }
        return false;
    }

    private void cancelGraceFailure(Bridge bridge) {
        java.util.concurrent.ScheduledFuture<?> scheduled = bridge.graceFailure;
        if (scheduled != null) {
            scheduled.cancel(false);
            bridge.graceFailure = null;
        }
    }

    private long openBridgeSessions(Bridge bridge) {
        bridge.sessions.removeIf(session -> session == null || !session.isOpen());
        return bridge.sessions.size();
    }

    /** The user the calling thread works for; without one only an unauthenticated local bridge could match. */
    private static String caller() {
        return CurrentUserContext.requiredUserId();
    }

    private static String owner(WebSocketSession session) {
        Object owner = session.getAttributes().get("jarvis.userId");
        return owner == null || String.valueOf(owner).isBlank() ? CurrentUserContext.LOCAL_USER_ID : String.valueOf(owner);
    }

    /** Never null: a user without a registered PC gets an empty bridge, so every request fails as "not connected". */
    private Bridge bridge(String owner) {
        Bridge bridge = bridges.get(owner);
        return bridge == null ? new Bridge() : bridge;
    }

    private long pendingCount(String owner) {
        return pending.values().stream().filter(entry -> entry.owner().equals(owner)).count();
    }

    private JsonNode request(String type, String serverId, Map<String, Object> payload, Duration timeout) {
        String owner = caller();
        WebSocketSession session = bridge(owner).session.get();
        if (session == null || !session.isOpen()) {
            throw new McpException("Windows MCP bridge is not connected" + (CurrentUserContext.LOCAL_USER_ID.equals(owner)
                    ? "." : " for this user: the Jarvis Windows app must be running and logged in to this account."));
        }
        String requestId = UUID.randomUUID().toString();
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(requestId, new Pending(owner, future));
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", type);
        message.put("requestId", requestId);
        message.put("serverId", serverId);
        message.put("payload", payload == null ? Map.of() : payload);
        long startedNano = System.nanoTime();
        LOGGER.info("[MCP_BRIDGE] event=REQUEST_SENT session={} type={} server={} requestId={} timeoutMs={}",
                session.getId(), type, serverId, requestId, timeout.toMillis());
        try {
            send(session, message);
            JsonNode response = future.get(timeout.plus(BRIDGE_RESPONSE_SLACK).toMillis(), TimeUnit.MILLISECONDS);
            LOGGER.info("[MCP_BRIDGE] event=RESPONSE_RECEIVED session={} type={} server={} requestId={} durationMs={}",
                    session.getId(), type, serverId, requestId, elapsedMs(startedNano));
            return response;
        } catch (TimeoutException exception) {
            pending.remove(requestId);
            LOGGER.warn("[MCP_BRIDGE] event=REQUEST_TIMEOUT session={} type={} server={} requestId={} timeoutSource=CORE_WAIT durationMs={}",
                    session.getId(), type, serverId, requestId, elapsedMs(startedNano));
            throw new McpException("Windows MCP bridge request timed out: " + type + " server=" + serverId, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            pending.remove(requestId);
            throw new McpException("Interrupted while waiting for Windows MCP bridge.", exception);
        } catch (Exception exception) {
            pending.remove(requestId);
            LOGGER.warn("[MCP_BRIDGE] event=REQUEST_FAILED session={} type={} server={} requestId={} durationMs={} error={}",
                    session.getId(), type, serverId, requestId, elapsedMs(startedNano), safeMessage(exception));
            if (exception instanceof McpException mcpException) {
                throw mcpException;
            }
            if (exception instanceof java.util.concurrent.ExecutionException
                    && exception.getCause() instanceof McpException bridgeFailure) {
                throw bridgeFailure;
            }
            throw new McpException("Windows MCP bridge request failed: " + type + " server=" + serverId, exception);
        }
    }

    private long elapsedMs(long startedNano) {
        return (System.nanoTime() - startedNano) / 1_000_000L;
    }

    private String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null ? throwable.getClass().getSimpleName() : message;
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
        String errorMessage = payload.path("errorMessage").asText("");
        if (!success && errorMessage.isBlank()) {
            errorMessage = errorMessage(content);
        }
        return new McpCallResult(
                success,
                content,
                objectMapper.convertValue(payload.path("structuredContent"), MAP_TYPE),
                payload.path("errorCode").asText(""),
                errorMessage
        );
    }

    private String errorMessage(List<McpContentItem> content) {
        String message = content.stream()
                .filter(item -> "text".equalsIgnoreCase(item.type()))
                .map(McpContentItem::text)
                .filter(text -> text != null && !text.isBlank())
                .findFirst()
                .orElse("");
        return message.isBlank() ? "MCP tool returned isError=true" : message;
    }

    private void completeAllExceptionally(String owner, String reason) {
        McpException exception = new McpException(reason);
        pending.forEach((requestId, entry) -> {
            if (entry.owner().equals(owner) && pending.remove(requestId, entry)) {
                entry.future().completeExceptionally(exception);
            }
        });
    }
}
