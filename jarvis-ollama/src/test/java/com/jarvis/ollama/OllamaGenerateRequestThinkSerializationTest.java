package com.jarvis.ollama;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for the exact mechanism the Qwen thinking-budget continuation call depends on:
 * {@code OllamaGenerateRequest.think} was widened from {@code String} to {@code Object} so a
 * {@link Boolean} can genuinely disable thinking (JSON {@code false}), while every existing
 * caller passing a reasoning-effort string (gpt-oss's "low"/"medium"/"high") keeps serializing
 * exactly as before.
 */
class OllamaGenerateRequestThinkSerializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void serializesAReasoningEffortStringUnchanged() throws Exception {
        OllamaGenerateRequest request = new OllamaGenerateRequest("gpt-oss:20b", "prompt", true, "low", "-1m", Map.of());

        String json = objectMapper.writeValueAsString(request);

        assertThat(json).contains("\"think\":\"low\"");
    }

    @Test
    void serializesBooleanFalseAsJsonBooleanNotString() throws Exception {
        OllamaGenerateRequest request = new OllamaGenerateRequest(
                "qwen3.5:9b", "prompt", true, Boolean.FALSE, "-1m", Map.of(), List.of());

        String json = objectMapper.writeValueAsString(request);

        assertThat(json).contains("\"think\":false");
        assertThat(json).doesNotContain("\"think\":\"false\"");
    }

    @Test
    void imagesFieldIsOmittedWhenEmptyPreservingTextOnlyRequestShape() throws Exception {
        OllamaGenerateRequest request = new OllamaGenerateRequest("gpt-oss:20b", "prompt", true, "medium", "-1m", Map.of());

        String json = objectMapper.writeValueAsString(request);

        assertThat(json).doesNotContain("images");
    }

    @Test
    void thinkFieldIsOmittedEntirelyWhenNullForModelsWithoutThinkingCapability() throws Exception {
        OllamaGenerateRequest request = new OllamaGenerateRequest(
                "ministral-3:14b", "prompt", true, null, "-1m", Map.of(), List.of());

        String json = objectMapper.writeValueAsString(request);

        assertThat(json).doesNotContain("think");
    }
}
