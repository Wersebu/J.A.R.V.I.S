package com.jarvis.tools.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MCP client for the "streamable HTTP" transport: every JSON-RPC message is POSTed to one URL and
 * the reply is either a JSON body or a short Server-Sent-Events stream carrying the response.
 *
 * <p>This is what hosted MCP servers use (e.g. GitHub's {@code https://api.githubcopilot.com/mcp/}
 * with {@code Authorization: Bearer <token>}), so Jarvis can reach them without installing anything
 * locally.</p>
 */
public class HttpMcpClient implements McpClient {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final String PROTOCOL_VERSION = "2025-03-26";

    private final String serverId;
    private final McpServerProperties properties;
    private final ObjectMapper objectMapper;
    private final String clientVersion;
    private final HttpClient httpClient;
    private final AtomicLong requestIds = new AtomicLong();
    private volatile McpConnectionState state = McpConnectionState.DISCONNECTED;
    private volatile String sessionId = "";
    private volatile String negotiatedVersion = "";

    /**
     * Creates the client.
     *
     * @param serverId server id
     * @param properties server properties ({@code url}, {@code headers}, timeouts)
     * @param objectMapper JSON mapper
     * @param clientVersion Jarvis version
     */
    public HttpMcpClient(String serverId, McpServerProperties properties, ObjectMapper objectMapper, String clientVersion) {
        this(serverId, properties, objectMapper, clientVersion, HttpClient.newBuilder()
                .connectTimeout(properties.getStartupTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    HttpMcpClient(String serverId, McpServerProperties properties, ObjectMapper objectMapper, String clientVersion,
                  HttpClient httpClient) {
        this.serverId = serverId;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clientVersion = clientVersion;
        this.httpClient = httpClient;
    }

    @Override
    public synchronized void initialize() {
        if (state == McpConnectionState.CONNECTED) {
            return;
        }
        if (properties.getUrl().isBlank()) {
            throw new McpException("MCP url is required for HTTP server '" + serverId + "'.");
        }
        state = McpConnectionState.CONNECTING;
        sessionId = "";
        negotiatedVersion = "";
        try {
            JsonNode result = request("initialize", Map.of(
                    "protocolVersion", PROTOCOL_VERSION,
                    "capabilities", Map.of(),
                    "clientInfo", Map.of("name", "jarvis-core", "version", clientVersion)
            ), properties.getInitializeTimeout());
            negotiatedVersion = result.path("protocolVersion").asText(PROTOCOL_VERSION);
            notifyInitialized();
            state = McpConnectionState.CONNECTED;
        } catch (RuntimeException exception) {
            state = McpConnectionState.ERROR;
            throw new McpException("Failed to initialize MCP server '" + serverId + "': " + exception.getMessage(), exception);
        }
    }

    @Override
    public List<McpToolDescriptor> listTools() {
        ensureConnected();
        List<McpToolDescriptor> descriptors = new ArrayList<>();
        String cursor = "";
        // tools/list is paginated; follow nextCursor (bounded) so large servers are fully discovered.
        for (int page = 0; page < 20; page++) {
            Map<String, Object> params = cursor.isBlank() ? Map.of() : Map.of("cursor", cursor);
            JsonNode result = request("tools/list", params, properties.getListToolsTimeout());
            for (JsonNode tool : result.path("tools")) {
                String name = tool.path("name").asText("");
                if (!name.isBlank()) {
                    descriptors.add(new McpToolDescriptor(serverId, name,
                            tool.path("description").asText("MCP tool " + name),
                            objectMapper.convertValue(tool.path("inputSchema"), MAP_TYPE),
                            properties.getAccessLevel()));
                }
            }
            cursor = result.path("nextCursor").asText("");
            if (cursor.isBlank()) {
                break;
            }
        }
        return descriptors;
    }

    @Override
    public McpCallResult callTool(String toolName, Map<String, Object> arguments, Duration timeout) {
        ensureConnected();
        JsonNode result = request("tools/call", Map.of(
                "name", toolName,
                "arguments", arguments == null ? Map.of() : arguments
        ), timeout == null ? properties.getCallTimeout() : timeout);
        List<McpContentItem> content = new ArrayList<>();
        for (JsonNode item : result.path("content")) {
            content.add(new McpContentItem(
                    item.path("type").asText("unknown"),
                    item.path("text").isMissingNode() ? null : item.path("text").asText(),
                    item.path("mimeType").isMissingNode() ? null : item.path("mimeType").asText(),
                    item.path("data").isMissingNode() ? null : item.path("data").asText(),
                    objectMapper.convertValue(item.path("annotations"), MAP_TYPE)));
        }
        boolean error = result.path("isError").asBoolean(false);
        String message = error ? content.stream().map(McpContentItem::text).filter(text -> text != null && !text.isBlank())
                .findFirst().orElse("MCP tool returned isError=true") : "";
        return new McpCallResult(!error, content, objectMapper.convertValue(result.path("structuredContent"), MAP_TYPE),
                error ? "MCP_TOOL_ERROR" : "", message);
    }

    @Override
    public McpConnectionState state() {
        return state;
    }

    @Override
    public void close() {
        String session = sessionId;
        if (!session.isBlank()) {
            try {
                HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(properties.getUrl()))
                        .timeout(Duration.ofSeconds(5)).DELETE();
                headers(builder);
                httpClient.send(builder.build(), HttpResponse.BodyHandlers.discarding());
            } catch (IOException | RuntimeException exception) {
                // Best effort session termination.
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
        sessionId = "";
        state = McpConnectionState.DISCONNECTED;
    }

    private void ensureConnected() {
        if (state != McpConnectionState.CONNECTED) {
            initialize();
        }
    }

    private void notifyInitialized() {
        post(Map.of("jsonrpc", "2.0", "method", "notifications/initialized", "params", Map.of()),
                properties.getInitializeTimeout(), -1);
    }

    private JsonNode request(String method, Map<String, Object> params, Duration timeout) {
        long id = requestIds.incrementAndGet();
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("jsonrpc", "2.0");
        message.put("id", id);
        message.put("method", method);
        message.put("params", params == null ? Map.of() : params);
        JsonNode response = post(message, timeout, id);
        if (response == null) {
            throw new McpException("MCP server '" + serverId + "' returned no response to " + method + ".");
        }
        if (response.hasNonNull("error")) {
            throw new McpException("MCP error from '" + serverId + "': " + response.path("error"));
        }
        return response.path("result");
    }

    /**
     * POSTs one message. Returns the JSON-RPC response with {@code expectedId}, or null for
     * notifications (expectedId &lt; 0).
     */
    private JsonNode post(Map<String, Object> message, Duration timeout, long expectedId) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(properties.getUrl()))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(message)));
            headers(builder);
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            response.headers().firstValue("Mcp-Session-Id").ifPresent(value -> sessionId = value);
            int status = response.statusCode();
            if (status == 404 && !sessionId.isBlank()) {
                state = McpConnectionState.ERROR;
                throw new McpException("MCP session expired for '" + serverId + "'; it will reconnect on next use.");
            }
            if (status == 401 || status == 403) {
                throw new McpException("MCP server '" + serverId + "' rejected the credentials (HTTP " + status
                        + "). Check the token in jarvis.mcp.servers." + serverId + ".headers.");
            }
            if (status < 200 || status >= 300) {
                throw new McpException("MCP server '" + serverId + "' returned HTTP " + status + ": " + abbreviate(response.body()));
            }
            if (expectedId < 0) {
                return null;
            }
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            return contentType.contains("text/event-stream")
                    ? fromEventStream(response.body(), expectedId)
                    : objectMapper.readTree(response.body());
        } catch (IOException exception) {
            throw new McpException("MCP HTTP failure for '" + serverId + "': " + exception.getMessage(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new McpException("Interrupted while calling MCP server '" + serverId + "'.", exception);
        }
    }

    JsonNode fromEventStream(String body, long expectedId) throws IOException {
        StringBuilder data = new StringBuilder();
        for (String line : (body + "\n\n").split("\\r?\\n", -1)) {
            if (line.startsWith("data:")) {
                data.append(line.substring(5).stripLeading()).append('\n');
            } else if (line.isEmpty() && !data.isEmpty()) {
                JsonNode node = objectMapper.readTree(data.toString());
                data.setLength(0);
                if (node.path("id").asLong(-1) == expectedId) {
                    return node;
                }
            }
        }
        return null;
    }

    private void headers(HttpRequest.Builder builder) {
        properties.getHeaders().forEach((name, value) -> {
            if (name != null && !name.isBlank() && value != null && !value.isBlank()) {
                builder.header(name, value);
            }
        });
        if (!sessionId.isBlank()) {
            builder.header("Mcp-Session-Id", sessionId);
        }
        if (!negotiatedVersion.isBlank()) {
            builder.header("MCP-Protocol-Version", negotiatedVersion);
        }
    }

    private static String abbreviate(String text) {
        String value = text == null ? "" : text.strip();
        return value.length() > 300 ? value.substring(0, 300) + "..." : value;
    }
}
