package com.jarvis.ollama;

/**
 * Dedicated service for communication with the Ollama HTTP API.
 */
public interface OllamaService {

    /**
     * Sends a prompt to Ollama and returns plain text output.
     *
     * @param prompt prompt text
     * @return generated response text
     */
    String generate(String prompt);
}
