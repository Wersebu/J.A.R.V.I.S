package com.jarvis.ollama;

/**
 * Non-streaming Ollama generate request.
 *
 * @param model model name
 * @param prompt prompt text
 * @param stream whether streaming is enabled
 * @param think native reasoning level for thinking-capable models
 */
public record OllamaGenerateRequest(String model, String prompt, boolean stream, String think) {
}
