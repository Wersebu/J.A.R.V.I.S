package com.jarvis.tools.mcp;

import com.jarvis.common.trace.AiTraceLogger;
import com.jarvis.common.trace.AiTraceSettings;
import com.jarvis.tools.ToolRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default MCP server manager.
 */
@Service
public class DefaultMcpServerManager implements McpServerManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultMcpServerManager.class);

    /**
     * Above this many consecutive "connected but tools/list returned 0 tools" discovery attempts
     * for one server, automatic rediscovery stops (never an infinite loop) - a manual reconnect
     * (POST /api/v1/mcp/{serverId}/connect) or a fresh Windows bridge registration still clears
     * this and starts a new bounded attempt window. This is deliberately distinct from the
     * ERROR/DISCONNECTED retry path below, which already retries on every {@link #discoverTools()}
     * call - a genuinely empty-but-connected result (e.g. Roblox Studio not attached yet) needs its
     * own bound so it does not either get cached as final "0 tools" forever, or hammer the bridge on
     * every single chat request while waiting for the external application to attach.
     */
    private static final int MAX_EMPTY_DISCOVERY_RETRIES = 5;

    /**
     * Minimum spacing between automatic empty-discovery retries for the same server, so a burst of
     * {@link #discoverTools()} calls (one per chat request) does not turn into a burst of redundant
     * {@code tools/list} round trips to the bridge while waiting for the external application.
     */
    private static final Duration EMPTY_DISCOVERY_RETRY_BACKOFF = Duration.ofSeconds(1);

    private final McpProperties properties;
    private final McpClientFactory clientFactory;
    private final WindowsMcpBridgeGateway windowsBridgeGateway;
    private final Map<String, McpClient> clients = new ConcurrentHashMap<>();
    private final Map<String, List<McpToolDescriptor>> discoveredTools = new ConcurrentHashMap<>();
    private final Map<String, McpConnectionState> states = new ConcurrentHashMap<>();
    private final Map<String, String> lastErrors = new ConcurrentHashMap<>();
    private final Map<String, Integer> emptyDiscoveryAttempts = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastDiscoveryAttempt = new ConcurrentHashMap<>();

    /**
     * Creates the manager.
     *
     * @param properties MCP properties
     * @param clientFactory MCP client factory
     */
    public DefaultMcpServerManager(
            McpProperties properties,
            McpClientFactory clientFactory,
            ObjectProvider<WindowsMcpBridgeGateway> windowsBridgeGateway
    ) {
        this.properties = properties;
        this.clientFactory = clientFactory;
        this.windowsBridgeGateway = windowsBridgeGateway.getIfAvailable();
    }

    @Override
    public void connect(String serverId) {
        connectInternal(serverId);
        // A public connect() call - the API's manual reconnect endpoint, or any other direct
        // caller - is always an intentional "try again" signal. Clear the bounded empty-discovery
        // bookkeeping so the next discoverTools() call gets a genuinely fresh attempt window
        // instead of still being suppressed by exhausted retries or an in-flight backoff left over
        // from before, even though the connection itself may have stayed CONNECTED throughout (an
        // exhausted empty-discovery outcome never flips the connection state away from CONNECTED,
        // so there is no other natural signal to hook this reset on).
        emptyDiscoveryAttempts.remove(serverId);
        lastDiscoveryAttempt.remove(serverId);
    }

    /**
     * Establishes (or confirms) the connection for {@code serverId} without touching the bounded
     * empty-discovery bookkeeping - used internally by {@link #discoverServerTools} on every
     * discovery attempt, where resetting that bookkeeping on every call would defeat the bound
     * entirely (see {@link #connect(String)} for the public, bookkeeping-resetting entry point).
     *
     * @param serverId server id
     */
    private void connectInternal(String serverId) {
        McpServerProperties server = server(serverId);
        if (!properties.isEnabled() || !server.isEnabled()) {
            states.put(serverId, McpConnectionState.DISCONNECTED);
            return;
        }
        if (isWindowsBridgeServer(server) && !bridgeConnected(server)) {
            states.put(serverId, McpConnectionState.DISCONNECTED);
            discoveredTools.remove(serverId);
            lastErrors.put(serverId, "Windows MCP bridge is not connected.");
            LOGGER.info("[MCP] waiting for Windows bridge server={}", serverId);
            return;
        }
        try {
            LOGGER.info("[MCP] connecting server={} host={} transport={}", serverId, server.getExecutionHost(), server.getTransport());
            states.put(serverId, McpConnectionState.CONNECTING);
            McpClient client = clients.computeIfAbsent(serverId, id -> clientFactory.create(id, server));
            client.initialize();
            states.put(serverId, McpConnectionState.CONNECTED);
            lastErrors.remove(serverId);
            LOGGER.info("[MCP] connected server={}", serverId);
        } catch (RuntimeException ex) {
            states.put(serverId, McpConnectionState.ERROR);
            lastErrors.put(serverId, ex.getMessage());
            LOGGER.warn("[MCP] connection failed server={} error={}", serverId, ex.getMessage());
        }
    }

    @Override
    public void disconnect(String serverId) {
        McpClient client = clients.remove(serverId);
        if (client != null) {
            LOGGER.info("[MCP] disconnecting server={}", serverId);
            client.close();
        }
        discoveredTools.remove(serverId);
        states.put(serverId, McpConnectionState.DISCONNECTED);
        emptyDiscoveryAttempts.remove(serverId);
        lastDiscoveryAttempt.remove(serverId);
        LOGGER.info("[MCP] disconnected server={}", serverId);
    }

    @Override
    public void activateWindowsBridgeServers() {
        if (!properties.isEnabled()) {
            return;
        }
        for (Map.Entry<String, McpServerProperties> entry : properties.getServers().entrySet()) {
            String serverId = entry.getKey();
            McpServerProperties server = entry.getValue();
            if (!server.isEnabled() || !isWindowsBridgeServer(server)) {
                continue;
            }
            LOGGER.info("[MCP_BRIDGE] activating Windows MCP server={}", serverId);
            closePreviousClient(serverId);
            discoveredTools.remove(serverId);
            // A fresh bridge registration (new Windows session, or the same session reconnecting)
            // deserves a clean bounded-retry window - never let attempts left over from a previous
            // bridge session suppress a legitimate first attempt against the new one.
            emptyDiscoveryAttempts.remove(serverId);
            lastDiscoveryAttempt.remove(serverId);
            List<McpToolDescriptor> tools = discoverServerTools(serverId, server);
            if (states.getOrDefault(serverId, McpConnectionState.DISCONNECTED) == McpConnectionState.CONNECTED) {
                discoveredTools.put(serverId, tools);
                recordDiscoveryOutcome(serverId, tools);
            }
            LOGGER.info("[MCP_BRIDGE] activation finished server={} state={} tools={}",
                    serverId,
                    states.getOrDefault(serverId, McpConnectionState.DISCONNECTED),
                    discoveredTools.getOrDefault(serverId, List.of()).size());
        }
    }

    /**
     * Closes and forgets any previously registered client for {@code serverId} before a fresh
     * activation replaces it. {@code clients.remove(serverId)} alone used to just drop the Java
     * reference - it never sent {@code MCP_DISCONNECT} to the Windows bridge, so the bridge never
     * knew to tear down the previous MCP process. On the next {@code MCP_CONNECT}, the Windows side
     * could then transparently reuse (or race against) that still-running, now-orphaned process
     * instead of starting a genuinely fresh one - see {@code WindowsMcpProcessClient} on the Windows
     * side for the matching process-tree-kill fix.
     */
    private void closePreviousClient(String serverId) {
        McpClient previous = clients.remove(serverId);
        if (previous == null) {
            return;
        }
        LOGGER.info("[MCP_BRIDGE] closing previous client before reactivation server={}", serverId);
        try {
            previous.close();
        } catch (RuntimeException exception) {
            LOGGER.warn("[MCP_BRIDGE] error closing previous client server={} error={}", serverId, exception.getMessage());
        }
    }

    @Override
    public void handleWindowsBridgeDisconnected() {
        for (Map.Entry<String, McpServerProperties> entry : properties.getServers().entrySet()) {
            String serverId = entry.getKey();
            McpServerProperties server = entry.getValue();
            if (!isWindowsBridgeServer(server)) {
                continue;
            }
            closePreviousClient(serverId);
            discoveredTools.remove(serverId);
            states.put(serverId, McpConnectionState.DISCONNECTED);
            lastErrors.put(serverId, "Windows MCP bridge disconnected.");
            emptyDiscoveryAttempts.remove(serverId);
            lastDiscoveryAttempt.remove(serverId);
            LOGGER.info("[MCP_BRIDGE] server marked disconnected server={}", serverId);
        }
    }

    @Override
    public List<McpServerStatus> statuses() {
        List<McpServerStatus> statuses = new ArrayList<>();
        for (Map.Entry<String, McpServerProperties> entry : properties.getServers().entrySet()) {
            String serverId = entry.getKey();
            McpServerProperties server = entry.getValue();
            statuses.add(new McpServerStatus(
                    serverId,
                    properties.isEnabled() && server.isEnabled(),
                    server.getExecutionHost(),
                    server.getTransport(),
                    states.getOrDefault(serverId, McpConnectionState.DISCONNECTED),
                    bridgeConnected(server),
                    discoveredTools.getOrDefault(serverId, List.of()).size(),
                    lastErrors.getOrDefault(serverId, "")
            ));
        }
        return List.copyOf(statuses);
    }

    @Override
    public List<McpToolDescriptor> discoverTools() {
        if (!properties.isEnabled()) {
            return List.of();
        }
        List<McpToolDescriptor> result = new ArrayList<>();
        for (Map.Entry<String, McpServerProperties> entry : properties.getServers().entrySet()) {
            String serverId = entry.getKey();
            McpServerProperties server = entry.getValue();
            if (!server.isEnabled()) {
                continue;
            }
            List<McpToolDescriptor> serverTools = discoveredTools.get(serverId);
            if (serverTools == null || shouldRediscover(serverId, serverTools)) {
                lastDiscoveryAttempt.put(serverId, Instant.now());
                serverTools = discoverServerTools(serverId, server);
                if (states.getOrDefault(serverId, McpConnectionState.DISCONNECTED) == McpConnectionState.CONNECTED) {
                    discoveredTools.put(serverId, serverTools);
                    recordDiscoveryOutcome(serverId, serverTools);
                } else {
                    discoveredTools.remove(serverId);
                }
            }
            result.addAll(serverTools);
        }
        return List.copyOf(result);
    }

    /**
     * Tracks consecutive "connected but 0 tools" outcomes so {@link #shouldRediscover} can bound
     * automatic retries instead of either caching an empty result forever or retrying unbounded on
     * every single {@link #discoverTools()} call.
     *
     * @param serverId server id
     * @param tools the tools just discovered for a genuinely CONNECTED server
     */
    private void recordDiscoveryOutcome(String serverId, List<McpToolDescriptor> tools) {
        if (!tools.isEmpty()) {
            emptyDiscoveryAttempts.remove(serverId);
            lastErrors.remove(serverId);
            return;
        }
        int attempts = emptyDiscoveryAttempts.merge(serverId, 1, Integer::sum);
        if (attempts >= MAX_EMPTY_DISCOVERY_RETRIES) {
            lastErrors.put(serverId, "MCP server connected but tools/list returned 0 tools after " + attempts
                    + " attempts. Check whether the external application is running and attached (e.g. Roblox "
                    + "Studio with Assistant -> Manage MCP Servers -> Enable Studio as MCP server), then retry "
                    + "via POST /api/v1/mcp/" + serverId + "/connect.");
            LOGGER.warn("[MCP] discovery exhausted server={} attempts={} - automatic retries stopped, manual reconnect required",
                    serverId, attempts);
        } else {
            LOGGER.info("[MCP] discovery connected but empty server={} attempts={}/{}", serverId, attempts, MAX_EMPTY_DISCOVERY_RETRIES);
        }
    }

    @Override
    public McpCallResult call(McpToolDescriptor descriptor, ToolRequest request) {
        McpServerProperties server = server(descriptor.serverId());
        connectInternal(descriptor.serverId());
        if (states.getOrDefault(descriptor.serverId(), McpConnectionState.ERROR) != McpConnectionState.CONNECTED) {
            return new McpCallResult(false, List.of(), Map.of(), "MCP_NOT_CONNECTED", lastErrors.getOrDefault(descriptor.serverId(), "MCP server is not connected."));
        }
        LOGGER.info("[MCP] call server={} tool={} requestId={}", descriptor.serverId(), descriptor.name(), request.requestId());
        // Deliberately shows both the model-facing name (mcp_<server>_<tool>) and the real tool
        // name about to be sent over the wire - a mismatch between the two is a plausible root
        // cause for a tool silently doing the wrong thing, and this is the actual transport
        // boundary where descriptor.name() is used, not a guess derived from the prefixed name.
        if (AiTraceSettings.logToolCalls()) {
            AiTraceLogger.logMcpCallBegin(request.requestId(), descriptor.serverId(), descriptor.jarvisToolName(),
                    descriptor.name(), request.arguments());
        }
        McpCallResult result = clients.get(descriptor.serverId()).callTool(descriptor.name(), request.arguments(), server.getCallTimeout());
        LOGGER.info("[MCP] completed server={} tool={} success={}", descriptor.serverId(), descriptor.name(), result.success());
        return result;
    }

    private List<McpToolDescriptor> discoverServerTools(String serverId, McpServerProperties server) {
        connectInternal(serverId);
        if (states.getOrDefault(serverId, McpConnectionState.ERROR) != McpConnectionState.CONNECTED) {
            LOGGER.info("[MCP] discovery skipped server={} reason=NOT_CONNECTED state={}",
                    serverId, states.getOrDefault(serverId, McpConnectionState.DISCONNECTED));
            return List.of();
        }
        long startedNano = System.nanoTime();
        LOGGER.info("[MCP] tools/list requested server={}", serverId);
        try {
            List<McpToolDescriptor> tools = clients.get(serverId).listTools();
            LOGGER.info("[MCP] discovered server={} tools={} names={} durationMs={}",
                    serverId, tools.size(), toolNamesSummary(tools), elapsedMs(startedNano));
            return List.copyOf(tools);
        } catch (RuntimeException ex) {
            states.put(serverId, McpConnectionState.ERROR);
            lastErrors.put(serverId, ex.getMessage());
            LOGGER.warn("[MCP] discovery failed server={} durationMs={} error={}", serverId, elapsedMs(startedNano), ex.getMessage());
            return List.of();
        }
    }

    private static final int MAX_LOGGED_TOOL_NAMES = 50;

    private String toolNamesSummary(List<McpToolDescriptor> tools) {
        List<String> names = tools.stream().map(McpToolDescriptor::name).limit(MAX_LOGGED_TOOL_NAMES).toList();
        String joined = String.join(",", names);
        return tools.size() > MAX_LOGGED_TOOL_NAMES ? joined + ",...(" + tools.size() + " total)" : joined;
    }

    private long elapsedMs(long startedNano) {
        return (System.nanoTime() - startedNano) / 1_000_000L;
    }

    private McpServerProperties server(String serverId) {
        McpServerProperties server = properties.getServers().get(serverId);
        if (server == null) {
            throw new McpException("Unknown MCP server: " + serverId);
        }
        return server;
    }

    private boolean bridgeConnected(McpServerProperties server) {
        return isWindowsBridgeServer(server)
                && windowsBridgeGateway != null
                && windowsBridgeGateway.isBridgeConnected();
    }

    private boolean isWindowsBridgeServer(McpServerProperties server) {
        return server.getExecutionHost() == McpExecutionHost.WINDOWS
                && server.getTransport() == McpTransport.WINDOWS_BRIDGE;
    }

    /**
     * Decides whether a cached empty tool list is worth re-attempting. A non-CONNECTED server
     * (ERROR/DISCONNECTED) is always retried on the next call, matching the previous behavior -
     * that path is already naturally bounded by how often {@link #discoverTools()} itself gets
     * called. A CONNECTED server with a genuinely empty {@code tools/list} result (e.g. Roblox
     * Studio not attached yet) is the case that previously got stuck forever: {@code CONNECTED}
     * looked like proof the empty list was final, so it was cached and never rechecked. That case
     * is now retried up to {@link #MAX_EMPTY_DISCOVERY_RETRIES} times with a small backoff between
     * attempts (see {@link #recordDiscoveryOutcome}) - bounded, not silently stuck, and not
     * hammering the bridge on every request either.
     *
     * @param serverId server id
     * @param serverTools the currently cached tool list for this server
     * @return true when a fresh discovery attempt should run
     */
    private boolean shouldRediscover(String serverId, List<McpToolDescriptor> serverTools) {
        if (!serverTools.isEmpty()) {
            return false;
        }
        if (states.getOrDefault(serverId, McpConnectionState.DISCONNECTED) != McpConnectionState.CONNECTED) {
            return true;
        }
        if (emptyDiscoveryAttempts.getOrDefault(serverId, 0) >= MAX_EMPTY_DISCOVERY_RETRIES) {
            return false;
        }
        Instant last = lastDiscoveryAttempt.get(serverId);
        return last == null || Duration.between(last, Instant.now()).compareTo(EMPTY_DISCOVERY_RETRY_BACKOFF) >= 0;
    }
}
