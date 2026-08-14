package com.jarvis.ollama;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for parsing Ollama's real {@code GET /api/tags} response shape, including the
 * {@code capabilities} array vision detection depends on. Fixture mirrors an actual response
 * captured from a running Ollama instance.
 */
class OllamaTagModelTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void parsesRealOllamaTagsResponseIncludingCapabilities() throws Exception {
        String json = """
                {"models":[{"name":"gpt-oss:20b","model":"gpt-oss:20b","modified_at":"2026-08-05T00:19:46.697Z",
                "size":13793441244,"digest":"17052f91","details":{"parent_model":"","format":"gguf","family":"gptoss",
                "families":["gptoss"],"parameter_size":"20.9B","quantization_level":"MXFP4"},
                "capabilities":["completion","tools","thinking"]}]}
                """;

        OllamaTagsResponse response = objectMapper.readValue(json, OllamaTagsResponse.class);

        assertThat(response.models()).hasSize(1);
        OllamaTagModel model = response.models().getFirst();
        assertThat(model.name()).isEqualTo("gpt-oss:20b");
        assertThat(model.size()).isEqualTo(13793441244L);
        assertThat(model.details().family()).isEqualTo("gptoss");
        assertThat(model.details().parameterSize()).isEqualTo("20.9B");
        assertThat(model.capabilities()).containsExactlyInAnyOrder("completion", "tools", "thinking");
        assertThat(model.capabilities()).doesNotContain("vision");
    }

    @Test
    void parsesAVisionCapableModelEntry() throws Exception {
        String json = """
                {"models":[{"name":"gemma3:4b","size":3300000000,
                "details":{"family":"gemma3","parameter_size":"4.3B"},
                "capabilities":["completion","tools","vision"]}]}
                """;

        OllamaTagsResponse response = objectMapper.readValue(json, OllamaTagsResponse.class);

        assertThat(response.models().getFirst().capabilities()).contains("vision");
    }

    @Test
    void toleratesAMissingCapabilitiesField() throws Exception {
        String json = "{\"models\":[{\"name\":\"legacy-model\",\"size\":1}]}";

        OllamaTagsResponse response = objectMapper.readValue(json, OllamaTagsResponse.class);

        assertThat(response.models().getFirst().capabilities()).isEmpty();
    }
}
