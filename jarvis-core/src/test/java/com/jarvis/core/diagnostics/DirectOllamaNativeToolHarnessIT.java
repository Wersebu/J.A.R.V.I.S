package com.jarvis.core.diagnostics;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.common.ai.NativeToolDefinition;
import com.jarvis.common.model.ActiveModelService;
import com.jarvis.core.JarvisApplication;
import com.jarvis.ollama.OllamaProperties;
import com.jarvis.tools.ToolManager;
import com.jarvis.tools.ToolRequest;
import com.jarvis.tools.ToolResult;
import com.jarvis.tools.runtime.NativeToolSchemaMapper;
import com.jarvis.tools.runtime.ToolAction;
import com.jarvis.tools.runtime.ToolIntent;
import com.jarvis.tools.workflow.ToolOperationClassifier;
import com.jarvis.tools.workflow.ToolOperationRole;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Manual diagnostic harness that bypasses the Jarvis cognitive pipeline but still uses the real
 * runtime tool catalog and ToolManager/MCP bridge. Enable deliberately when investigating native
 * Ollama tool selection. It must remain disabled for normal CI/release runs.
 */
@Disabled("Manual diagnostic harness. Requires local Ollama and a connected Roblox Studio MCP bridge.")
@SpringBootTest(classes = JarvisApplication.class)
class DirectOllamaNativeToolHarnessIT {

    private static final Logger LOGGER = LoggerFactory.getLogger(DirectOllamaNativeToolHarnessIT.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final String USER_QUESTION =
            "Podaj liste folderow dostepnych w aktualnie polaczonym projekcie Roblox Studio.";

    @Autowired
    private NativeToolSchemaMapper schemaMapper;

    @Autowired
    private ToolManager toolManager;

    @Autowired
    private ActiveModelService activeModelService;

    @Autowired
    private OllamaProperties ollamaProperties;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void directOllamaRobloxFolderInspectionHarness() throws Exception {
        List<NativeToolDefinition> nativeTools = schemaMapper.definitions(ToolIntent.NO_TOOL, USER_QUESTION,
                "List folders in the currently connected Roblox Studio project.");
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", "You are in a diagnostic native tool loop. Use read-only Roblox MCP tools for connected Roblox Studio state. Web tools are only for public internet/docs. Do not modify Roblox Studio. Continue up to six turns until you have folder paths."));
        messages.add(Map.of("role", "user", "content", USER_QUESTION));

        String endpoint = normalizeBaseUrl(ollamaProperties.baseUrl()) + "/api/chat";
        String model = activeModelService.activeModel();
        LOGGER.info("[DIRECT_OLLAMA_HARNESS] model={} tools={}", model, nativeTools.size());

        for (int turn = 1; turn <= 6; turn++) {
            JsonNode response = postChat(endpoint, model, messages, nativeTools);
            JsonNode message = response.path("message");
            JsonNode toolCalls = message.path("tool_calls");
            LOGGER.info("[DIRECT_OLLAMA_HARNESS] turn={} rawToolCalls={}", turn, toolCalls);
            if (!toolCalls.isArray() || toolCalls.isEmpty()) {
                LOGGER.info("[DIRECT_OLLAMA_HARNESS] finalContent={}", message.path("content").asText(""));
                return;
            }
            messages.add(Map.of(
                    "role", "assistant",
                    "content", message.path("content").asText(""),
                    "tool_calls", objectMapper.convertValue(toolCalls, List.class)
            ));
            for (JsonNode call : toolCalls) {
                String id = call.path("id").asText("call-" + turn);
                JsonNode function = call.path("function");
                String functionName = function.path("name").asText("");
                Map<String, Object> arguments = arguments(function.path("arguments"));
                ToolAction action = schemaMapper.toAction(functionName, arguments, "Direct Ollama diagnostic harness");
                assertReadOnly(action);
                ToolResult result = toolManager.execute(new ToolRequest(action.tool(), action.operation(),
                        "direct-ollama-harness", "direct-ollama-harness", action.reason(), "", action.arguments()));
                messages.add(Map.of(
                        "role", "tool",
                        "tool_call_id", id,
                        "content", objectMapper.writeValueAsString(result.data())
                ));
            }
        }
        LOGGER.warn("[DIRECT_OLLAMA_HARNESS] stopped after max turns without final answer");
    }

    private JsonNode postChat(
            String endpoint,
            String model,
            List<Map<String, Object>> messages,
            List<NativeToolDefinition> nativeTools
    ) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("messages", messages);
        payload.put("tools", nativeTools.stream().map(this::ollamaTool).toList());
        payload.put("stream", false);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofMinutes(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new AssertionError("Ollama /api/chat failed status=" + response.statusCode() + " body=" + response.body());
        }
        return objectMapper.readTree(response.body());
    }

    private Map<String, Object> ollamaTool(NativeToolDefinition definition) {
        return Map.of("type", "function", "function", Map.of(
                "name", definition.name(),
                "description", definition.description(),
                "parameters", definition.parameters()
        ));
    }

    private Map<String, Object> arguments(JsonNode node) throws Exception {
        if (node.isObject()) {
            return objectMapper.convertValue(node, MAP_TYPE);
        }
        if (node.isTextual() && !node.asText().isBlank()) {
            return objectMapper.readValue(node.asText(), MAP_TYPE);
        }
        return Map.of();
    }

    private void assertReadOnly(ToolAction action) {
        ToolOperationRole role = ToolOperationClassifier.classify(action.tool(), action.operation());
        if (role == ToolOperationRole.WRITE || role == ToolOperationRole.EXECUTE || action.tool().contains("execute")) {
            throw new AssertionError("Refusing mutating diagnostic tool call: " + action.tool() + "." + action.operation());
        }
    }

    private String normalizeBaseUrl(String baseUrl) {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }
}
