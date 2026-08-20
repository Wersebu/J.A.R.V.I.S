package com.jarvis.ollama;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for sending images to a chat-templated multimodal model via {@code /api/chat}'s
 * per-message {@code images} field, instead of {@code /api/generate}'s top-level field - which at
 * least one real model (a Gemma build) silently never reads, per live testing.
 */
class OllamaChatMessageImageSerializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void imagesAreAttachedToTheMessageNotTheTopLevelRequest() throws Exception {
        OllamaChatMessage message = new OllamaChatMessage("user", "Co jest na tym zdjeciu?", "", List.of(), "", List.of("base64data"));
        OllamaChatRequest request = new OllamaChatRequest("gemma4:12b", List.of(message), List.of(), true, "low", "-1m", Map.of());

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(request));

        assertThat(json.path("messages").get(0).path("images").get(0).asText()).isEqualTo("base64data");
        assertThat(json.has("images")).isFalse();
    }

    @Test
    void messagesWithoutImagesOmitTheFieldEntirely() throws Exception {
        OllamaChatMessage message = new OllamaChatMessage("user", "hello", "", List.of(), "");

        String json = objectMapper.writeValueAsString(message);

        assertThat(json).doesNotContain("images");
    }

    @Test
    void chatContinuationThinkFalseSerializesAsJsonBoolean() throws Exception {
        OllamaChatRequest request = new OllamaChatRequest("qwen3.5:9b", List.of(), List.of(), true, Boolean.FALSE, "-1m", Map.of());

        String json = objectMapper.writeValueAsString(request);

        assertThat(json).contains("\"think\":false");
        assertThat(json).doesNotContain("\"think\":\"false\"");
    }

    @Test
    void thinkFieldIsOmittedEntirelyWhenNullForModelsWithoutThinkingCapability() throws Exception {
        OllamaChatRequest request = new OllamaChatRequest("ministral-3:14b", List.of(), List.of(), true, null, "-1m", Map.of());

        String json = objectMapper.writeValueAsString(request);

        assertThat(json).doesNotContain("think");
    }

    // Regression coverage for the confirmed root cause of a real "no user query found in messages"
    // HTTP 500 during multi-turn native tool continuation: a tool-role message with no tool_name
    // field at all. See NativeToolLoopServiceMultiTurnToolResultTest for the end-to-end flow.

    @Test
    void toolResultMessageSerializesWithExactToolNameAndToolCallId() throws Exception {
        OllamaChatMessage message = new OllamaChatMessage(
                "tool", "{\"session_id\":\"studio-123\"}", "", List.of(), "call_123", "list_sessions", List.of());

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(message));

        assertThat(json.path("role").asText()).isEqualTo("tool");
        assertThat(json.path("tool_name").asText()).isEqualTo("list_sessions");
        assertThat(json.path("tool_call_id").asText()).isEqualTo("call_123");
        assertThat(json.path("content").asText()).isEqualTo("{\"session_id\":\"studio-123\"}");
    }

    @Test
    void toolNameFieldIsOmittedEntirelyForNonToolMessages() throws Exception {
        OllamaChatMessage assistantMessage = new OllamaChatMessage("assistant", "hello", "", List.of(), "");

        String json = objectMapper.writeValueAsString(assistantMessage);

        assertThat(json).doesNotContain("tool_name");
    }
}
