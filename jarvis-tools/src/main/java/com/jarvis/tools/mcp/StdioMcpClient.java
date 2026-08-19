package com.jarvis.tools.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Minimal JSON-RPC over stdio MCP client.
 */
public class StdioMcpClient implements McpClient {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final String serverId;
    private final McpServerProperties properties;
    private final ObjectMapper objectMapper;
    private final String clientVersion;
    private final AtomicLong requestIds = new AtomicLong();
    private ExecutorService readerExecutor;

    private volatile McpConnectionState state = McpConnectionState.DISCONNECTED;
    private Process process;
    private BufferedWriter writer;
    private BufferedReader reader;

    /**
     * Creates the client.
     *
     * @param serverId server id
     * @param properties server properties
     * @param objectMapper JSON mapper
     */
    public StdioMcpClient(String serverId, McpServerProperties properties, ObjectMapper objectMapper, String clientVersion) {
        this.serverId = serverId;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clientVersion = clientVersion;
        this.readerExecutor = newReaderExecutor();
    }

    @Override
    public synchronized void initialize() {
        if (state == McpConnectionState.CONNECTED) {
            return;
        }
        if (properties.getCommand().isBlank()) {
            throw new McpException("MCP command is required for server '" + serverId + "'.");
        }
        state = McpConnectionState.CONNECTING;
        try {
            List<String> command = new ArrayList<>();
            command.add(properties.getCommand());
            command.addAll(properties.getArgs());
            process = new ProcessBuilder(command).start();
            writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            if (readerExecutor.isShutdown()) {
                readerExecutor = newReaderExecutor();
            }
            drainErrors(process);
            request("initialize", Map.of(
                    "protocolVersion", "2024-11-05",
                    "capabilities", Map.of(),
                    "clientInfo", Map.of("name", "jarvis-core", "version", clientVersion)
            ), properties.getInitializeTimeout());
            state = McpConnectionState.CONNECTED;
        } catch (Exception ex) {
            state = McpConnectionState.ERROR;
            close();
            throw new McpException("Failed to initialize MCP server '" + serverId + "'.", ex);
        }
    }

    @Override
    public List<McpToolDescriptor> listTools() {
        ensureConnected();
        JsonNode result = request("tools/list", Map.of(), properties.getListToolsTimeout());
        List<McpToolDescriptor> descriptors = new ArrayList<>();
        JsonNode tools = result.path("tools");
        if (!tools.isArray()) {
            return List.of();
        }
        for (JsonNode tool : tools) {
            String name = tool.path("name").asText("");
            if (name.isBlank()) {
                continue;
            }
            Map<String, Object> inputSchema = objectMapper.convertValue(tool.path("inputSchema"), MAP_TYPE);
            descriptors.add(new McpToolDescriptor(
                    serverId,
                    name,
                    tool.path("description").asText("MCP tool " + name),
                    inputSchema,
                    properties.getAccessLevel()
            ));
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
        return toCallResult(result);
    }

    @Override
    public McpConnectionState state() {
        return state;
    }

    @Override
    public synchronized void close() {
        state = state == McpConnectionState.CONNECTED ? McpConnectionState.STOPPING : state;
        closeReader();
        closeWriter();
        if (process != null) {
            process.destroy();
        }
        readerExecutor.shutdownNow();
        state = McpConnectionState.DISCONNECTED;
    }

    private ExecutorService newReaderExecutor() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "mcp-" + serverId + "-reader");
            thread.setDaemon(true);
            return thread;
        });
    }

    private void ensureConnected() {
        if (state != McpConnectionState.CONNECTED) {
            initialize();
        }
    }

    private synchronized JsonNode request(String method, Map<String, Object> params, Duration timeout) {
        long id = requestIds.incrementAndGet();
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        request.put("params", params == null ? Map.of() : params);
        try {
            writer.write(objectMapper.writeValueAsString(request));
            writer.write('\n');
            writer.flush();
            JsonNode response = readResponse(id, timeout);
            if (response.hasNonNull("error")) {
                throw new McpException("MCP error from '" + serverId + "': " + response.path("error"));
            }
            return response.path("result");
        } catch (IOException ex) {
            state = McpConnectionState.ERROR;
            throw new McpException("MCP stdio failure for '" + serverId + "'.", ex);
        }
    }

    private JsonNode readResponse(long id, Duration timeout) {
        CompletableFuture<JsonNode> future = CompletableFuture.supplyAsync(() -> {
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    JsonNode node = objectMapper.readTree(line);
                    if (node.path("id").asLong(-1) == id) {
                        return node;
                    }
                }
                throw new McpException("MCP server '" + serverId + "' closed stdout.");
            } catch (IOException ex) {
                throw new McpException("Failed to read MCP response from '" + serverId + "'.", ex);
            }
        }, readerExecutor);
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            state = McpConnectionState.ERROR;
            close();
            throw new McpException("MCP request timed out for '" + serverId + "'.", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new McpException("Interrupted while waiting for MCP response from '" + serverId + "'.", ex);
        } catch (Exception ex) {
            throw new McpException("Failed to receive MCP response from '" + serverId + "'.", ex);
        }
    }

    private McpCallResult toCallResult(JsonNode result) {
        List<McpContentItem> content = new ArrayList<>();
        JsonNode contentNode = result.path("content");
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
        Map<String, Object> structured = objectMapper.convertValue(result.path("structuredContent"), MAP_TYPE);
        boolean error = result.path("isError").asBoolean(false);
        return new McpCallResult(!error, content, structured, error ? "MCP_TOOL_ERROR" : "",
                error ? errorMessage(content) : "");
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

    private void drainErrors(Process childProcess) {
        Thread thread = new Thread(() -> {
            try (BufferedReader stderr = new BufferedReader(new InputStreamReader(childProcess.getErrorStream(), StandardCharsets.UTF_8))) {
                while (stderr.readLine() != null) {
                    // Drain stderr so the child process cannot block.
                }
            } catch (IOException ignored) {
                // Best-effort stream drain.
            }
        }, "mcp-" + serverId + "-stderr");
        thread.setDaemon(true);
        thread.start();
    }

    private void closeWriter() {
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException ignored) {
                // Best effort close.
            }
        }
    }

    private void closeReader() {
        if (reader != null) {
            try {
                reader.close();
            } catch (IOException ignored) {
                // Best effort close.
            }
        }
    }
}
