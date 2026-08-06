package com.jarvis.api.dto;

/**
 * Request for updating editable system instructions.
 *
 * @param instructions system instructions
 */
public record SystemPromptRequest(String instructions) {
}
