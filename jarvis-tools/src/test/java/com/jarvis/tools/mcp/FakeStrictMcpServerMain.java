package com.jarvis.tools.mcp;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal strict stdio MCP server used by {@link StdioMcpClientTest}: prints a banner on stdout,
 * refuses tools/list until notifications/initialized arrived, pings the client before answering
 * tools/call, and returns the FAKE_TOKEN environment variable from the "whoami" tool.
 */
public final class FakeStrictMcpServerMain {

    private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*(\\d+)");

    private FakeStrictMcpServerMain() {
    }

    public static void main(String[] args) throws Exception {
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        out.println("fake-mcp starting (banner on stdout)");
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        boolean initialized = false;
        String line;
        while ((line = in.readLine()) != null) {
            Matcher matcher = ID.matcher(line);
            String id = matcher.find() ? matcher.group(1) : null;
            if (line.contains("\"notifications/initialized\"")) {
                initialized = true;
            } else if (line.contains("\"initialize\"")) {
                out.println("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{}}}");
            } else if (line.contains("\"tools/list\"")) {
                if (!initialized) {
                    out.println("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"error\":{\"code\":-32002,\"message\":\"not initialized\"}}");
                } else {
                    out.println("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"tools\":[{\"name\":\"whoami\",\"description\":\"d\",\"inputSchema\":{\"type\":\"object\",\"properties\":{}}},{\"name\":\"delete_repo\",\"inputSchema\":{\"type\":\"object\"}}]}}");
                }
            } else if (line.contains("\"tools/call\"")) {
                out.println("{\"jsonrpc\":\"2.0\",\"id\":\"srv-1\",\"method\":\"ping\"}");
                String pong = in.readLine();
                String token = System.getenv().getOrDefault("FAKE_TOKEN", "missing");
                boolean pongOk = pong != null && pong.contains("\"srv-1\"") && pong.contains("\"result\"");
                out.println("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"token="
                        + token + " pong=" + pongOk + "\"}]}}");
            }
        }
    }
}
