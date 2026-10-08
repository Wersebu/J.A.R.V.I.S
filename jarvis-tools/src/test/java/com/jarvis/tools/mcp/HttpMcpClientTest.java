package com.jarvis.tools.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpMcpClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<String> methods = new CopyOnWriteArrayList<>();
    private final List<String> authHeaders = new CopyOnWriteArrayList<>();
    private final List<String> sessionHeaders = new CopyOnWriteArrayList<>();
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void initializesWithSessionPaginatesToolsAndReadsSseCallResult() throws Exception {
        String url = start(false);
        HttpMcpClient client = new HttpMcpClient("github", properties(url, "Bearer t0ken"), objectMapper, "test");

        client.initialize();
        List<McpToolDescriptor> tools = client.listTools();
        McpCallResult result = client.callTool("get_me", Map.of(), Duration.ofSeconds(5));

        assertThat(tools).extracting(McpToolDescriptor::name).containsExactly("get_me", "list_issues");
        assertThat(result.success()).isTrue();
        assertThat(result.content().get(0).text()).isEqualTo("hello from sse");
        assertThat(methods).containsExactly("initialize", "notifications/initialized", "tools/list", "tools/list", "tools/call");
        assertThat(authHeaders).allMatch("Bearer t0ken"::equals);
        assertThat(sessionHeaders.subList(1, sessionHeaders.size())).allMatch("session-42"::equals);
    }

    @Test
    void unauthorizedGivesAClearMessage() throws Exception {
        String url = start(true);
        HttpMcpClient client = new HttpMcpClient("github", properties(url, "Bearer wrong"), objectMapper, "test");

        assertThatThrownBy(client::initialize).hasMessageContaining("rejected the credentials");
    }

    private McpServerProperties properties(String url, String auth) {
        McpServerProperties properties = new McpServerProperties();
        properties.setTransport(McpTransport.HTTP);
        properties.setUrl(url);
        properties.setHeaders(Map.of("Authorization", auth));
        return properties;
    }

    private String start(boolean rejectAll) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            JsonNode request = objectMapper.readTree(exchange.getRequestBody());
            String method = request.path("method").asText();
            methods.add(method);
            authHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            sessionHeaders.add(String.valueOf(exchange.getRequestHeaders().getFirst("Mcp-Session-Id")));
            if (rejectAll) {
                exchange.sendResponseHeaders(401, -1);
                exchange.close();
                return;
            }
            long id = request.path("id").asLong(-1);
            String body;
            String type = "application/json";
            switch (method) {
                case "initialize" -> {
                    exchange.getResponseHeaders().add("Mcp-Session-Id", "session-42");
                    body = "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{}}}";
                }
                case "notifications/initialized" -> {
                    exchange.sendResponseHeaders(202, -1);
                    exchange.close();
                    return;
                }
                case "tools/list" -> body = request.path("params").has("cursor")
                        ? "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"tools\":[{\"name\":\"list_issues\",\"inputSchema\":{\"type\":\"object\"}}]}}"
                        : "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"tools\":[{\"name\":\"get_me\",\"inputSchema\":{\"type\":\"object\"}}],\"nextCursor\":\"p2\"}}";
                default -> {
                    type = "text/event-stream";
                    body = "event: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{}}\n\n"
                            + "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":" + id
                            + ",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"hello from sse\"}]}}\n\n";
                }
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", type);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
    }
}
