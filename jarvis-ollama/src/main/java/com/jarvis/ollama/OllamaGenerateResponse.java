package com.jarvis.ollama;

/**
 * Minimal Ollama generate response used by Jarvis.
 *
 * @param response generated text
 */
public record OllamaGenerateResponse(String response) {
}
