package com.jarvis.ollama;

import java.util.Map;

/**
 * Ollama function call payload.
 *
 * @param name function name
 * @param arguments structured function arguments
 */
public record OllamaToolFunction(
        String name,
        Map<String, Object> arguments
) {
}
