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
}
