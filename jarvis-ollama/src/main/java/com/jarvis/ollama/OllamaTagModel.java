package com.jarvis.ollama;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * One installed model entry as reported by Ollama's {@code GET /api/tags} endpoint.
 *
 * @param name model tag, e.g. {@code gpt-oss:20b}
 * @param size on-disk size in bytes
 * @param details provider-reported model details, when available
 * @param capabilities provider-reported capability strings, e.g. {@code ["completion","vision","tools"]}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OllamaTagModel(String name, Long size, OllamaTagModelDetails details, List<String> capabilities) {

    /**
     * Creates a tag model, defaulting a missing capability list to empty.
     */
    public OllamaTagModel {
        capabilities = capabilities == null ? List.of() : capabilities;
    }

    /**
     * Provider-reported model family and parameter size.
     *
     * @param family model family, e.g. {@code llama}
     * @param parameterSize human-readable parameter size, e.g. {@code 20.9B}
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OllamaTagModelDetails(String family, @JsonProperty("parameter_size") String parameterSize) {
    }
}
